-- Completion marker for the parse pipeline: stamped once by
-- ParsedExamPersister.finalizeExam when an exam successfully flips parsing→draft.
-- Lets the UI tell a *parse* failure (never finalized) from an *evaluation*
-- failure (parsed fine, later failed/cancelled while solving) reliably, instead
-- of inferring it from task count — tasks are committed before finalize, so a
-- failure/cancel in that window leaves tasks present on a never-parsed exam.
-- Nullable; only set on successful parse completion.
ALTER TABLE exams ADD COLUMN parsed_at timestamptz;

-- Backfill so existing rows keep their current classification: any PDF exam that
-- clearly parsed (advanced past parsing, or is failed but has tasks — the old
-- "eval failure" heuristic) is marked complete. Legacy failed PDF exams with no
-- tasks stay unmarked → still treated as parse failures.
UPDATE exams SET parsed_at = updated_at
 WHERE source = 'pdf'
   AND (status IN ('draft', 'ready', 'evaluating', 'grading', 'finished')
        OR (status = 'failed' AND EXISTS (SELECT 1 FROM tasks t WHERE t.exam_id = exams.id)));
