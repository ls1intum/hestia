-- Older extraction code treated competency-tree synthesis as optional. Reclassify the latest run
-- when it published extracted skills but no terminal competency, so the UI exposes the failure and
-- offers Retry instead of presenting an apparently successful empty tree.
UPDATE extraction_run run
SET status = 'FAILED',
    error = COALESCE(
        error,
        'Competency tree synthesis did not complete. Retry the extraction.'
    )
WHERE run.status = 'SUCCEEDED'
  AND run.goals_created > 0
  AND run.id = (
      SELECT MAX(latest.id)
      FROM extraction_run latest
      WHERE latest.course_id = run.course_id
  )
  AND EXISTS (
      SELECT 1
      FROM learning_goal goal
      WHERE goal.course_id = run.course_id
        AND goal.origin = 'EXTRACTED'
        AND goal.role = 'SKILL'
  )
  AND NOT EXISTS (
      SELECT 1
      FROM learning_goal goal
      WHERE goal.course_id = run.course_id
        AND goal.origin = 'TERMINAL'
  );
