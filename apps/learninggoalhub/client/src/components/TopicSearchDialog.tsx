import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client.ts";
import type { LearningGoal } from "../api/client.ts";
import {
  createTopicFromSlides,
  findTopicPages,
  readTopicPages,
  type ProposedSubSkill,
  type TopicProposal,
} from "../lib/topicSearch.ts";
import Button from "./Button.tsx";

/** One page range in the picker; `manual` ranges were added by hand and can be removed. */
type PickedRange = {
  id: string;
  documentId: number;
  label: string;
  startPage: number;
  endPage: number;
  ticked: boolean;
  manual: boolean;
};

function Spinner() {
  return (
    <span
      aria-hidden="true"
      className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-current/40 border-t-current"
    />
  );
}

const validRange = (range: PickedRange) =>
  Number.isInteger(range.startPage) &&
  Number.isInteger(range.endPage) &&
  range.startPage >= 1 &&
  range.endPage >= range.startPage;

const pageCount = (range: PickedRange) =>
  validRange(range) ? range.endPage - range.startPage + 1 : 0;

/**
 * "Find in the slides": adds the topic an instructor typed from the pages that teach it. First the
 * instructor settles which pages to read, starting from the runs the text search found; then the
 * pages are read and the sub-skills found there are shown under the skills they group into, each
 * with its page and quote. Only what stays ticked is created.
 */
export default function TopicSearchDialog({
  courseId,
  topic,
  onClose,
  onCreated,
}: {
  courseId: number;
  topic: string;
  onClose: () => void;
  onCreated: (topic: LearningGoal) => void;
}) {
  const queryClient = useQueryClient();

  const pagesQuery = useQuery({
    queryKey: ["topic-search-pages", courseId, topic],
    queryFn: () => findTopicPages(courseId, topic),
    gcTime: 0,
    refetchOnWindowFocus: false,
  });
  const documentsQuery = useQuery({
    queryKey: ["documents", courseId],
    queryFn: async () => {
      const { data, error } = await api.GET("/api/courses/{courseId}/documents", {
        params: { path: { courseId } },
      });
      if (error || !data) throw new Error("Could not load the documents.");
      return data;
    },
  });

  const [edited, setEdited] = useState<PickedRange[] | null>(null);
  const proposed = useMemo<PickedRange[]>(
    () =>
      (pagesQuery.data?.runs ?? []).map((run, index) => ({
        id: `run-${index}`,
        documentId: run.documentId!,
        label: run.label ?? "",
        startPage: run.startPage!,
        endPage: run.endPage!,
        ticked: true,
        manual: false,
      })),
    [pagesQuery.data],
  );
  const ranges = edited ?? proposed;
  const updateRange = (id: string, change: Partial<PickedRange>) =>
    setEdited(ranges.map((range) => (range.id === id ? { ...range, ...change } : range)));

  const [manualDocument, setManualDocument] = useState<number | "">("");
  const [manualStart, setManualStart] = useState("");
  const [manualEnd, setManualEnd] = useState("");

  const maxPages = pagesQuery.data?.maxPages ?? 80;
  const chosen = ranges.filter((range) => range.ticked);
  const totalPages = chosen.reduce((sum, range) => sum + pageCount(range), 0);
  const invalidRange = chosen.some((range) => !validRange(range));

  const [proposal, setProposal] = useState<TopicProposal | null>(null);
  const [kept, setKept] = useState<Set<string>>(() => new Set());

  const readMutation = useMutation({
    mutationFn: () =>
      readTopicPages(
        courseId,
        topic,
        chosen.map((range) => ({
          documentId: range.documentId,
          startPage: range.startPage,
          endPage: range.endPage,
        })),
      ),
    onSuccess: (result) => {
      setProposal(result);
      const keys = [
        ...(result.skills ?? []).flatMap((skill) => skill.subSkills ?? []),
        ...(result.direct ?? []),
      ].map((subSkill) => subSkill.key!);
      setKept(new Set(keys));
    },
  });

  const createMutation = useMutation({
    mutationFn: () => createTopicFromSlides(courseId, proposal!.proposalId!, [...kept]),
    onSuccess: async (created) => {
      await queryClient.invalidateQueries({ queryKey: ["goals", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["course", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["courses"] });
      onCreated(created);
    },
  });

  const busy = readMutation.isPending || createMutation.isPending;
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !busy) onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [busy, onClose]);

  const documentName = (documentId: number | undefined) => {
    const document = documentsQuery.data?.find((d) => d.id === documentId);
    return document?.displayName || document?.filename || "";
  };

  const addManualRange = () => {
    const start = Number(manualStart);
    const end = Number(manualEnd || manualStart);
    if (manualDocument === "" || !Number.isInteger(start) || !Number.isInteger(end)) return;
    setEdited([
      ...ranges,
      {
        id: `manual-${Date.now()}`,
        documentId: manualDocument,
        label: documentName(manualDocument),
        startPage: start,
        endPage: end,
        ticked: true,
        manual: true,
      },
    ]);
    setManualStart("");
    setManualEnd("");
  };

  const toggleKept = (key: string) =>
    setKept((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  const renderSubSkill = (subSkill: ProposedSubSkill, duplicate = false) => {
    const key = subSkill.key!;
    const source = subSkill.source;
    return (
      <li key={key} className={`flex gap-2.5 py-2 ${duplicate ? "opacity-60" : ""}`}>
        {duplicate ? (
          <span aria-hidden="true" className="mt-1 h-3.5 w-3.5 shrink-0" />
        ) : (
          <input
            type="checkbox"
            className="mt-1 h-3.5 w-3.5 shrink-0 accent-[var(--hestia-primary)]"
            checked={kept.has(key)}
            onChange={() => toggleKept(key)}
            disabled={createMutation.isPending}
            aria-label={subSkill.text}
          />
        )}
        <div className="flex min-w-0 flex-col gap-1">
          <span className="text-sm text-hestia-text">{subSkill.text}</span>
          <span className="text-xs text-hestia-text-muted">
            {[
              source?.page != null ? `p. ${source.page}` : null,
              source?.displayName,
              subSkill.bloom?.toLowerCase(),
            ]
              .filter(Boolean)
              .join(" · ")}
          </span>
          {source?.snippet && (
            <blockquote className="line-clamp-2 border-l-2 border-hestia-border pl-2 text-xs italic text-hestia-text-muted">
              {source.snippet}
            </blockquote>
          )}
          {duplicate && (
            <span className="text-xs text-hestia-text-muted">
              {subSkill.duplicateOf?.topicText
                ? `Already under “${subSkill.duplicateOf.topicText}”`
                : "Already in the course"}
              {subSkill.duplicateOf?.text ? `: ${subSkill.duplicateOf.text}` : ""}
            </span>
          )}
          {!duplicate && (subSkill.knowledge?.length ?? 0) > 0 && (
            <ul className="flex flex-wrap gap-1">
              {subSkill.knowledge!.map((item, index) => (
                <li
                  key={index}
                  className="rounded-full border border-hestia-border px-2 py-0.5 text-[11px] text-hestia-text-muted"
                  title={item.text}
                >
                  {item.shortLabel || item.text}
                </li>
              ))}
            </ul>
          )}
        </div>
      </li>
    );
  };

  const newCount =
    (proposal?.skills ?? []).reduce((sum, skill) => sum + (skill.subSkills?.length ?? 0), 0) +
    (proposal?.direct?.length ?? 0);

  return (
    <div
      className="fixed inset-0 z-[60]"
      role="dialog"
      aria-modal="true"
      aria-labelledby="topic-search-title"
    >
      <div aria-hidden="true" className="absolute inset-0 bg-hestia-bg/90" />
      <div
        onClick={busy ? undefined : onClose}
        className="absolute inset-0 flex items-start justify-center overflow-y-auto p-4 sm:p-8"
      >
        <div
          onClick={(e) => e.stopPropagation()}
          className="comp-unfold flex w-full max-w-2xl flex-col gap-3.5 sm:mt-[6vh]"
        >
          <div className="flex min-w-0 flex-col gap-1">
            <span
              id="topic-search-title"
              className="text-xs font-semibold uppercase tracking-wider text-hestia-text"
            >
              Find in the slides
            </span>
            <p className="text-sm text-hestia-text">“{topic}”</p>
          </div>

          <div className="flex flex-col gap-4 rounded-lg border border-hestia-border bg-hestia-surface p-4 shadow-lg">
            {proposal == null ? (
              readMutation.isPending ? (
                <div className="flex items-center gap-2.5 py-6 text-sm text-hestia-text-muted">
                  <Spinner />
                  Reading {totalPages} pages. This can take a minute…
                </div>
              ) : pagesQuery.isLoading ? (
                <div className="flex items-center gap-2.5 py-6 text-sm text-hestia-text-muted">
                  <Spinner />
                  Searching the slides…
                </div>
              ) : pagesQuery.isError ? (
                <p className="text-sm text-hestia-danger">
                  {(pagesQuery.error as Error).message}
                </p>
              ) : (
                <>
                  <p className="text-xs text-hestia-text-muted">
                    Searched for {(pagesQuery.data?.terms ?? []).map((term) => `“${term}”`).join(", ")}.
                    Untick or adjust the pages to read.
                  </p>
                  {ranges.length === 0 ? (
                    <p className="text-sm text-hestia-text-muted">
                      No pages mention this topic. Add a page range by hand below.
                    </p>
                  ) : (
                    <ul className="flex flex-col divide-y divide-hestia-border">
                      {ranges.map((range) => (
                        <li key={range.id} className="flex items-center gap-2.5 py-2">
                          <input
                            type="checkbox"
                            className="h-3.5 w-3.5 shrink-0 accent-[var(--hestia-primary)]"
                            checked={range.ticked}
                            onChange={() => updateRange(range.id, { ticked: !range.ticked })}
                            aria-label={`Read ${range.label}`}
                          />
                          <div className="flex min-w-0 flex-1 flex-col">
                            <span className="truncate text-sm text-hestia-text">{range.label}</span>
                            {documentName(range.documentId) !== range.label && (
                              <span className="truncate text-xs text-hestia-text-muted">
                                {documentName(range.documentId)}
                              </span>
                            )}
                          </div>
                          <label className="flex shrink-0 items-center gap-1 text-xs text-hestia-text-muted">
                            pp.
                            <PageInput
                              value={range.startPage}
                              onChange={(page) => updateRange(range.id, { startPage: page })}
                              label="First page"
                            />
                            –
                            <PageInput
                              value={range.endPage}
                              onChange={(page) => updateRange(range.id, { endPage: page })}
                              label="Last page"
                            />
                          </label>
                          {range.manual && (
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => setEdited(ranges.filter((r) => r.id !== range.id))}
                            >
                              Remove
                            </Button>
                          )}
                        </li>
                      ))}
                    </ul>
                  )}
                  <div className="flex flex-wrap items-center gap-1.5 border-t border-hestia-border pt-3">
                    <select
                      value={manualDocument}
                      onChange={(e) =>
                        setManualDocument(e.target.value === "" ? "" : Number(e.target.value))
                      }
                      className="min-w-0 flex-1 rounded-sm border-[1.5px] border-hestia-border bg-hestia-surface px-2 py-1 text-xs text-hestia-text"
                      aria-label="Document"
                    >
                      <option value="">Add pages from…</option>
                      {(documentsQuery.data ?? []).map((document) => (
                        <option key={document.id} value={document.id}>
                          {document.displayName || document.filename}
                        </option>
                      ))}
                    </select>
                    <input
                      value={manualStart}
                      onChange={(e) => setManualStart(e.target.value)}
                      inputMode="numeric"
                      placeholder="from"
                      aria-label="First page"
                      className="w-14 rounded-sm border-[1.5px] border-hestia-border bg-hestia-surface px-2 py-1 text-xs text-hestia-text"
                    />
                    <input
                      value={manualEnd}
                      onChange={(e) => setManualEnd(e.target.value)}
                      inputMode="numeric"
                      placeholder="to"
                      aria-label="Last page"
                      className="w-14 rounded-sm border-[1.5px] border-hestia-border bg-hestia-surface px-2 py-1 text-xs text-hestia-text"
                    />
                    <Button
                      variant="neutral"
                      size="sm"
                      onClick={addManualRange}
                      disabled={manualDocument === "" || manualStart.trim() === ""}
                    >
                      Add range
                    </Button>
                  </div>
                  {readMutation.isError && (
                    <p role="alert" className="text-sm text-hestia-danger">
                      {(readMutation.error as Error).message}
                    </p>
                  )}
                  <div className="flex items-center justify-between gap-2">
                    <span
                      className={`text-xs tabular-nums ${
                        totalPages > maxPages ? "text-hestia-danger" : "text-hestia-text-muted"
                      }`}
                    >
                      {totalPages} of at most {maxPages} pages
                      {totalPages > maxPages && ". Untick or shorten a range."}
                    </span>
                    <div className="flex gap-2">
                      <Button variant="neutral" onClick={onClose}>
                        Cancel
                      </Button>
                      <Button
                        onClick={() => readMutation.mutate()}
                        disabled={totalPages === 0 || totalPages > maxPages || invalidRange}
                      >
                        Read {totalPages} pages
                      </Button>
                    </div>
                  </div>
                </>
              )
            ) : (
              <>
                <p className="text-xs text-hestia-text-muted">
                  Read {proposal.pagesRead} pages. Untick what does not belong to this topic.
                </p>
                {newCount === 0 ? (
                  <p className="text-sm text-hestia-text-muted">
                    Nothing new was found on these pages.
                  </p>
                ) : (
                  <div className="flex flex-col gap-3">
                    {(proposal.skills ?? []).map((skill) => (
                      <section key={skill.key} className="flex flex-col">
                        <h4 className="text-sm font-semibold text-hestia-text">{skill.text}</h4>
                        <ul className="ml-1 flex flex-col divide-y divide-hestia-border border-l-2 border-hestia-border pl-3">
                          {(skill.subSkills ?? []).map((subSkill) => renderSubSkill(subSkill))}
                        </ul>
                      </section>
                    ))}
                    {(proposal.direct?.length ?? 0) > 0 && (
                      <section className="flex flex-col">
                        {(proposal.skills?.length ?? 0) > 0 && (
                          <h4 className="text-sm font-semibold text-hestia-text">
                            Directly under the topic
                          </h4>
                        )}
                        <ul className="flex flex-col divide-y divide-hestia-border">
                          {proposal.direct!.map((subSkill) => renderSubSkill(subSkill))}
                        </ul>
                      </section>
                    )}
                  </div>
                )}
                {(proposal.duplicates?.length ?? 0) > 0 && (
                  <section className="flex flex-col border-t border-hestia-border pt-3">
                    <h4 className="text-xs font-semibold uppercase tracking-wider text-hestia-text-muted">
                      Already in the course
                    </h4>
                    <ul className="flex flex-col divide-y divide-hestia-border">
                      {proposal.duplicates!.map((subSkill) => renderSubSkill(subSkill, true))}
                    </ul>
                  </section>
                )}
                {createMutation.isError && (
                  <p role="alert" className="text-sm text-hestia-danger">
                    {(createMutation.error as Error).message}
                  </p>
                )}
                <div className="flex items-center justify-between gap-2">
                  <Button
                    variant="ghost"
                    onClick={() => {
                      setProposal(null);
                      readMutation.reset();
                      createMutation.reset();
                    }}
                    disabled={createMutation.isPending}
                  >
                    Back to pages
                  </Button>
                  <div className="flex gap-2">
                    <Button variant="neutral" onClick={onClose} disabled={createMutation.isPending}>
                      Cancel
                    </Button>
                    <Button
                      onClick={() => createMutation.mutate()}
                      disabled={kept.size === 0 || createMutation.isPending}
                    >
                      {createMutation.isPending ? (
                        <>
                          <Spinner />
                          Creating…
                        </>
                      ) : (
                        `Create topic with ${kept.size} sub-skill${kept.size === 1 ? "" : "s"}`
                      )}
                    </Button>
                  </div>
                </div>
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function PageInput({
  value,
  onChange,
  label,
}: {
  value: number;
  onChange: (page: number) => void;
  label: string;
}) {
  return (
    <input
      type="number"
      min={1}
      value={Number.isFinite(value) ? value : ""}
      onChange={(e) => onChange(e.target.value === "" ? NaN : Number(e.target.value))}
      aria-label={label}
      className="w-14 rounded-sm border-[1.5px] border-hestia-border bg-hestia-surface px-1.5 py-0.5 text-xs tabular-nums text-hestia-text"
    />
  );
}
