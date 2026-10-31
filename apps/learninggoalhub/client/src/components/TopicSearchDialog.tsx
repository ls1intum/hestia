import { useEffect, useMemo, useRef, useState } from "react";
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
import { DOCUMENT_KIND_LABEL } from "../lib/documents.ts";
import Button from "./Button.tsx";
import { useFocusTrap } from "../lib/useFocusTrap.ts";

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

const pages = (count: number) => `${count} ${count === 1 ? "page" : "pages"}`;

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
  const allTicked = chosen.length === ranges.length;
  // One row per document: a document the search hit several times lists its ranges side by side.
  const groups: { documentId: number; ranges: PickedRange[] }[] = [];
  for (const range of ranges) {
    const group = groups.find((g) => g.documentId === range.documentId);
    if (group) group.ranges.push(range);
    else groups.push({ documentId: range.documentId, ranges: [range] });
  }

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
  const dialogRef = useRef<HTMLDivElement>(null);
  useFocusTrap(dialogRef);
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

  const sourceLine = (subSkill: ProposedSubSkill) =>
    [
      subSkill.source?.page != null ? `p. ${subSkill.source.page}` : null,
      subSkill.source?.displayName,
    ]
      .filter(Boolean)
      .join(" · ");

  const toggleKept = (key: string) =>
    setKept((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  const renderSubSkill = (subSkill: ProposedSubSkill) => {
    const key = subSkill.key!;
    const source = subSkill.source;
    const ticked = kept.has(key);
    return (
      <li
        key={key}
        className={`flex gap-2.5 rounded-md border p-3 transition-colors ${
          ticked
            ? "border-hestia-primary/50 bg-hestia-primary-muted/25"
            : "border-hestia-border"
        }`}
      >
        <input
          type="checkbox"
          className="mt-1 h-3.5 w-3.5 shrink-0 accent-[var(--hestia-primary)]"
          checked={ticked}
          onChange={() => toggleKept(key)}
          disabled={createMutation.isPending}
          aria-label={subSkill.text}
        />
        <div className="flex min-w-0 flex-1 flex-col gap-2">
          <div className="flex items-start justify-between gap-2">
            <span className="text-sm font-medium text-hestia-text">{subSkill.text}</span>
            {subSkill.bloom && (
              <span className="shrink-0 rounded-full bg-hestia-bg px-2 py-0.5 text-[11px] font-medium capitalize text-hestia-text-muted">
                {subSkill.bloom.toLowerCase()}
              </span>
            )}
          </div>
          {source?.snippet ? (
            <figure className="flex flex-col gap-1 border-l-2 border-hestia-border pl-2.5">
              <blockquote className="line-clamp-2 text-xs text-hestia-text-muted">
                {source.snippet}
              </blockquote>
              <figcaption className="text-[11px] text-hestia-text-muted">
                {sourceLine(subSkill)}
              </figcaption>
            </figure>
          ) : (
            <span className="text-[11px] text-hestia-text-muted">{sourceLine(subSkill)}</span>
          )}
          {(subSkill.knowledge?.length ?? 0) > 0 && (
            <div className="flex flex-col gap-1">
              <span className="text-[11px] font-semibold uppercase tracking-wider text-hestia-text-muted">
                Knowledge
              </span>
              <ul className="flex flex-wrap gap-1">
                {subSkill.knowledge!.map((item, index) => (
                  <li
                    key={index}
                    className="rounded-full bg-hestia-bg px-2 py-0.5 text-[11px] text-hestia-text"
                    title={item.text}
                  >
                    {item.shortLabel || item.text}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </li>
    );
  };

  const newCount =
    (proposal?.skills ?? []).reduce((sum, skill) => sum + (skill.subSkills?.length ?? 0), 0) +
    (proposal?.direct?.length ?? 0);
  const duplicateCount = proposal?.duplicates?.length ?? 0;
  // Duplicates are only counted, never listed: when they all sit under one topic, the count names it.
  const duplicateTopics = new Set(
    (proposal?.duplicates ?? []).map((subSkill) => subSkill.duplicateOf?.topicText ?? ""),
  );
  const sharedDuplicateTopic = duplicateTopics.size === 1 ? [...duplicateTopics][0] : "";
  const newKeys = [
    ...(proposal?.skills ?? []).flatMap((skill) => skill.subSkills ?? []),
    ...(proposal?.direct ?? []),
  ].map((subSkill) => subSkill.key!);

  return (
    <div
      ref={dialogRef}
      tabIndex={-1}
      className="fixed inset-0 z-[60] focus:outline-none"
      role="dialog"
      aria-modal="true"
      aria-labelledby="topic-search-title"
    >
      <div aria-hidden="true" className="absolute inset-0 bg-hestia-bg" />
      <div
        onClick={busy ? undefined : onClose}
        className="absolute inset-0 flex items-start justify-center overflow-y-auto p-4 sm:p-8"
      >
        <div
          onClick={(e) => e.stopPropagation()}
          className="comp-unfold flex w-full max-w-2xl flex-col gap-3.5 sm:mt-[6vh]"
        >
          <div className="flex flex-col gap-4 rounded-lg border border-hestia-border bg-hestia-surface p-4 shadow-lg">
            <div className="flex min-w-0 flex-col gap-0.5 border-b border-hestia-border pb-3">
              <span
                id="topic-search-title"
                className="text-xs font-semibold uppercase tracking-wider text-hestia-text-muted"
              >
                Find in the slides
              </span>
              <p className="truncate text-base font-semibold text-hestia-text">“{topic}”</p>
            </div>
            {proposal == null ? (
              readMutation.isPending ? (
                <div className="flex items-center gap-2.5 py-6 text-sm text-hestia-text-muted">
                  <Spinner />
                  Reading {pages(totalPages)}. This can take a minute…
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
                  <div className="flex flex-col gap-1.5">
                    <p className="text-sm font-semibold text-hestia-text">Choose the pages to read</p>
                    <div className="flex flex-wrap items-center gap-1 text-xs text-hestia-text-muted">
                      Searched for
                      {(pagesQuery.data?.terms ?? []).map((term) => (
                        <span
                          key={term}
                          className="rounded-full border border-hestia-border px-2 py-0.5 text-hestia-text"
                        >
                          {term}
                        </span>
                      ))}
                    </div>
                  </div>
                  {ranges.length === 0 ? (
                    <p className="text-sm text-hestia-text-muted">
                      No pages mention this topic. Add a page range by hand below.
                    </p>
                  ) : (
                    <div className="flex flex-col gap-1">
                      <div className="flex items-center justify-between gap-2">
                        <span className="text-xs tabular-nums text-hestia-text-muted">
                          {chosen.length} of {ranges.length} page ranges selected
                        </span>
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() =>
                            setEdited(ranges.map((range) => ({ ...range, ticked: !allTicked })))
                          }
                        >
                          {allTicked ? "Select none" : "Select all"}
                        </Button>
                      </div>
                      <ul className="flex max-h-[45vh] flex-col divide-y divide-hestia-border overflow-y-auto border-t border-hestia-border pr-1">
                        {groups.map((group) => {
                          const document = documentsQuery.data?.find(
                            (d) => d.id === group.documentId,
                          );
                          const name = documentName(group.documentId) || group.ranges[0].label;
                          const tickedCount = group.ranges.filter((range) => range.ticked).length;
                          const allInGroup = tickedCount === group.ranges.length;
                          return (
                            <li key={group.documentId} className="flex items-start gap-2.5 py-2">
                              <input
                                type="checkbox"
                                className="mt-1 h-3.5 w-3.5 shrink-0 accent-[var(--hestia-primary)]"
                                checked={allInGroup}
                                ref={(el) => {
                                  if (el) el.indeterminate = tickedCount > 0 && !allInGroup;
                                }}
                                onChange={() =>
                                  setEdited(
                                    ranges.map((range) =>
                                      range.documentId === group.documentId
                                        ? { ...range, ticked: !allInGroup }
                                        : range,
                                    ),
                                  )
                                }
                                aria-label={`Read ${name}`}
                              />
                              <div className="flex min-w-0 flex-1 items-center gap-1.5 py-0.5">
                                <span className="truncate text-sm text-hestia-text">{name}</span>
                                {document?.kind && (
                                  <span className="shrink-0 rounded-full border border-hestia-border px-1.5 text-[11px] text-hestia-text-muted">
                                    {DOCUMENT_KIND_LABEL[document.kind]}
                                  </span>
                                )}
                              </div>
                              <ul className="flex shrink-0 flex-col items-end gap-1">
                                {group.ranges.map((range) => (
                                  <li
                                    key={range.id}
                                    className="flex items-center gap-1 text-xs text-hestia-text-muted"
                                  >
                                    {range.label !== name && (
                                      <span className="max-w-40 truncate">{range.label}</span>
                                    )}
                                    {group.ranges.length > 1 && (
                                      <input
                                        type="checkbox"
                                        className="h-3.5 w-3.5 shrink-0 accent-[var(--hestia-primary)]"
                                        checked={range.ticked}
                                        onChange={() =>
                                          updateRange(range.id, { ticked: !range.ticked })
                                        }
                                        aria-label={`Read pages ${range.startPage} to ${range.endPage} of ${name}`}
                                      />
                                    )}
                                    p.
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
                                    {range.manual && (
                                      <Button
                                        variant="ghost"
                                        size="sm"
                                        onClick={() =>
                                          setEdited(ranges.filter((r) => r.id !== range.id))
                                        }
                                      >
                                        Remove
                                      </Button>
                                    )}
                                  </li>
                                ))}
                              </ul>
                            </li>
                          );
                        })}
                      </ul>
                    </div>
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
                        Read {pages(totalPages)}
                      </Button>
                    </div>
                  </div>
                </>
              )
            ) : (
              <>
                <div className="flex items-start justify-between gap-2">
                  <div className="flex flex-col gap-0.5">
                    <p className="text-sm font-semibold text-hestia-text">
                      Choose the sub-skills to add
                    </p>
                    <p className="text-xs tabular-nums text-hestia-text-muted">
                      Read {pages(proposal.pagesRead ?? 0)} · {newCount} new
                      {duplicateCount > 0 &&
                        ` · ${duplicateCount} already ${
                          sharedDuplicateTopic
                            ? `under the topic “${sharedDuplicateTopic}”`
                            : "in the course"
                        }`}
                    </p>
                  </div>
                  {newCount > 1 && (
                    <Button
                      variant="ghost"
                      size="sm"
                      disabled={createMutation.isPending}
                      onClick={() =>
                        setKept(kept.size === newCount ? new Set() : new Set(newKeys))
                      }
                    >
                      {kept.size === newCount ? "Select none" : "Select all"}
                    </Button>
                  )}
                </div>
                <div className="flex max-h-[55vh] flex-col gap-4 overflow-y-auto">
                  {newCount === 0 ? (
                    <p className="rounded-md bg-hestia-bg/60 px-3 py-2.5 text-sm text-hestia-text-muted">
                      {duplicateCount > 0
                        ? "Nothing new: every sub-skill found on these pages already exists in the course."
                        : "Nothing new was found on these pages."}
                    </p>
                  ) : (
                    <div className="flex flex-col gap-4">
                      {(proposal.skills ?? []).map((skill) => (
                        <section key={skill.key} className="flex flex-col gap-2">
                          <h4 className="text-sm font-semibold text-hestia-text">{skill.text}</h4>
                          <ul className="flex flex-col gap-2">
                            {(skill.subSkills ?? []).map((subSkill) => renderSubSkill(subSkill))}
                          </ul>
                        </section>
                      ))}
                      {(proposal.direct?.length ?? 0) > 0 && (
                        <section className="flex flex-col gap-2">
                          {(proposal.skills?.length ?? 0) > 0 && (
                            <h4 className="text-sm font-semibold text-hestia-text">
                              Directly under the topic
                            </h4>
                          )}
                          <ul className="flex flex-col gap-2">
                            {proposal.direct!.map((subSkill) => renderSubSkill(subSkill))}
                          </ul>
                        </section>
                      )}
                    </div>
                  )}
                </div>
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
      className="w-10 rounded-sm border-[1.5px] border-hestia-border bg-hestia-surface px-1 py-0.5 text-center text-xs tabular-nums text-hestia-text [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
    />
  );
}
