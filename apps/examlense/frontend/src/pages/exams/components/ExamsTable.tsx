import { useMemo, useState, type ReactNode } from "react";
import { ArrowDown, ArrowUp, ChevronsUpDown, ListFilter } from "lucide-react";
import type { ExamListItem } from "@/lib/api/api-client";
import { fuzzyMatch } from "@/lib/utils/fuzzy";
import { progressSortValue } from "@/lib/exam/exam-progress";
import { EXAM_STATUS_META } from "@/lib/exam/exam-status";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  Pagination,
  PaginationContent,
  PaginationItem,
  PaginationLink,
  PaginationNext,
  PaginationPrevious,
} from "@/components/ui/pagination";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Checkbox } from "@/components/ui/checkbox";
import { ModelLogo } from "@/components/shared/ModelLogo";
import { cn } from "@/lib/utils/utils";
import { ExamTableRow, type ExamRowHandlers } from "./ExamTableRow";

type ExamStatus = ExamListItem["status"];

/** One selectable option in a column filter popover. */
interface FilterOption {
  id: string;
  label: string;
  /** Optional leading visual (model logo / status chip). */
  leading?: ReactNode;
}

/**
 * Fixed set of solver models offered as filter options in the Solver column
 * popover. Deliberately a curated subset of the solver catalog — icons come
 * from `ModelLogo` (shared with the row cell), labels use the short product
 * naming.
 */
const SOLVER_OPTIONS: FilterOption[] = [
  { id: "gpt-5.5", label: "GPT 5.5" },
  { id: "claude-opus-4-8", label: "Opus 4.8" },
  { id: "gemini-3.5-flash", label: "Gemini 3.5" },
  { id: "qwen3.6-35b-a3b", label: "Qwen 3.6" },
].map((o) => ({
  ...o,
  leading: (
    <span className="inline-flex h-5 w-5 items-center justify-center p-0.5">
      <ModelLogo modelId={o.id} />
    </span>
  ),
}));

const SOLVER_FILTER_IDS = SOLVER_OPTIONS.map((o) => o.id);

/**
 * Status filter options for the Status column, grouped by the badge label a user
 * actually sees: `ready` and `failed` both render as "Draft", so they filter
 * under it. Each option's id maps to one or more underlying exam statuses.
 */
const STATUS_FILTER_GROUPS: { id: ExamStatus; statuses: ExamStatus[] }[] = [
  { id: "parsing", statuses: ["parsing"] },
  { id: "draft", statuses: ["draft", "ready", "failed"] },
  { id: "evaluating", statuses: ["evaluating"] },
  { id: "grading", statuses: ["grading"] },
  { id: "finished", statuses: ["finished"] },
];

const STATUS_FILTER_IDS = STATUS_FILTER_GROUPS.map((g) => g.id);

/** Map every exam status to the filter-option id it belongs to. */
const STATUS_TO_FILTER_ID: Record<string, string> = Object.fromEntries(
  STATUS_FILTER_GROUPS.flatMap((g) => g.statuses.map((s) => [s, g.id])),
);

const STATUS_OPTIONS: FilterOption[] = STATUS_FILTER_GROUPS.map(({ id }) => {
  const meta = EXAM_STATUS_META[id];
  const Icon = meta.Icon;
  return {
    id,
    label: meta.label,
    leading: (
      <span
        className={cn(
          "inline-flex h-5 w-5 items-center justify-center rounded-full",
          meta.className,
        )}
      >
        <Icon size={11} />
      </span>
    ),
  };
});

const PAGE_SIZE = 10;

type SortKey = "title" | "progress" | "created";
type SortDir = "asc" | "desc";

/** Default direction when a column is first selected. */
const DEFAULT_DIR: Record<SortKey, SortDir> = {
  title: "asc",
  progress: "desc",
  created: "desc",
};

const compare = (a: ExamListItem, b: ExamListItem, key: SortKey): number => {
  switch (key) {
    case "title":
      return (a.title || "Untitled exam").localeCompare(b.title || "Untitled exam");
    case "progress":
      return progressSortValue(a) - progressSortValue(b);
    case "created":
      return new Date(a.created_at).getTime() - new Date(b.created_at).getTime();
  }
};

/**
 * Toggle one option in a multi-select column filter. An empty set means "Show
 * all" (every option reads as implied-checked), so the first click there narrows
 * by starting from the full list; selecting everything collapses back to empty
 * (no filter) so retired values (e.g. old solver models) stay visible.
 */
const toggleFilter = (prev: Set<string>, id: string, allIds: string[]): Set<string> => {
  const next = prev.size === 0 ? new Set(allIds) : new Set(prev);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  return next.size === allIds.length ? new Set() : next;
};

/** Build a compact page-number list with ellipses for many pages. */
const pageItems = (current: number, total: number): (number | "…")[] => {
  if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1);
  const items: (number | "…")[] = [1];
  const start = Math.max(2, current - 1);
  const end = Math.min(total - 1, current + 1);
  if (start > 2) items.push("…");
  for (let p = start; p <= end; p++) items.push(p);
  if (end < total - 1) items.push("…");
  items.push(total);
  return items;
};

const SortableHead = ({
  label,
  columnKey,
  sortKey,
  sortDir,
  onSort,
  className,
}: {
  label: string;
  columnKey: SortKey;
  sortKey: SortKey;
  sortDir: SortDir;
  onSort: (key: SortKey) => void;
  className?: string;
}) => {
  const active = sortKey === columnKey;
  const Icon = !active ? ChevronsUpDown : sortDir === "asc" ? ArrowUp : ArrowDown;
  return (
    <TableHead className={className}>
      <button
        type="button"
        onClick={() => onSort(columnKey)}
        aria-sort={active ? (sortDir === "asc" ? "ascending" : "descending") : "none"}
        className={cn(
          "inline-flex items-center gap-1 rounded-hestia-sm transition-colors hover:text-hestia-text",
          active && "text-hestia-text",
        )}
      >
        {label}
        <Icon size={13} className={cn(!active && "opacity-50")} />
      </button>
    </TableHead>
  );
};

const FilterHead = ({
  label,
  ariaLabel,
  options,
  selected,
  onToggle,
  onClear,
  className,
}: {
  label: string;
  ariaLabel: string;
  options: FilterOption[];
  selected: Set<string>;
  onToggle: (id: string) => void;
  onClear: () => void;
  className?: string;
}) => {
  // Empty selection is the "Show all" (no filter) state; a non-empty set is an
  // active filter. When showing all, every option reads as implied-checked but
  // muted to signal it isn't an explicit, narrowing selection.
  const showAll = selected.size === 0;
  return (
    <TableHead className={className}>
      <Popover>
        <PopoverTrigger asChild>
          <button
            type="button"
            aria-label={ariaLabel}
            className={cn(
              "inline-flex items-center gap-1 rounded-hestia-sm transition-colors hover:text-hestia-text",
              !showAll && "text-hestia-text",
            )}
          >
            {label}
            <ListFilter size={13} className={cn(showAll && "opacity-50")} />
            {!showAll && (
              <span className="ml-0.5 inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-hestia-primary px-1 text-[10px] font-semibold leading-none text-white">
                {selected.size}
              </span>
            )}
          </button>
        </PopoverTrigger>
        <PopoverContent align="start" className="w-52 p-1">
          <button
            type="button"
            onClick={onClear}
            className="flex w-full items-center gap-2 rounded-hestia-sm px-2 py-1.5 text-sm text-hestia-text transition-colors hover:bg-hestia-primary-muted/30"
          >
            <Checkbox checked={showAll} className="pointer-events-none" tabIndex={-1} />
            Show all
          </button>
          <div className="my-1 h-px bg-hestia-border" />
          {options.map((opt) => (
            <button
              key={opt.id}
              type="button"
              onClick={() => onToggle(opt.id)}
              className={cn(
                "flex w-full items-center gap-2 rounded-hestia-sm px-2 py-1.5 text-sm text-hestia-text transition-colors hover:bg-hestia-primary-muted/30",
                // Muted while in the show-all state: implied-on, not an explicit filter.
                showAll && "opacity-50",
              )}
            >
              <Checkbox
                checked={showAll || selected.has(opt.id)}
                className="pointer-events-none"
                tabIndex={-1}
              />
              {opt.leading}
              {opt.label}
            </button>
          ))}
        </PopoverContent>
      </Popover>
    </TableHead>
  );
};

export const ExamsTable = ({
  exams,
  handlers,
  query,
}: {
  exams: ExamListItem[];
  handlers: ExamRowHandlers;
  /** Controlled Title search text (the input lives in the page header). */
  query: string;
}) => {
  const [sortKey, setSortKey] = useState<SortKey>("created");
  const [sortDir, setSortDir] = useState<SortDir>("desc");
  const [solverFilter, setSolverFilter] = useState<Set<string>>(new Set());
  const [statusFilter, setStatusFilter] = useState<Set<string>>(new Set());
  const [page, setPage] = useState(1);

  const onSort = (key: SortKey) => {
    if (key === sortKey) {
      setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    } else {
      setSortKey(key);
      setSortDir(DEFAULT_DIR[key]);
    }
    setPage(1);
  };

  const toggleSolver = (id: string) => {
    setSolverFilter((prev) => toggleFilter(prev, id, SOLVER_FILTER_IDS));
    setPage(1);
  };

  const clearSolver = () => {
    setSolverFilter(new Set());
    setPage(1);
  };

  const toggleStatus = (id: string) => {
    setStatusFilter((prev) => toggleFilter(prev, id, STATUS_FILTER_IDS));
    setPage(1);
  };

  const clearStatus = () => {
    setStatusFilter(new Set());
    setPage(1);
  };

  const filtered = useMemo(() => {
    const matched = exams.filter((e) => {
      if (query.trim().length > 0 && fuzzyMatch(query, e.title || "Untitled exam") === null)
        return false;
      if (solverFilter.size > 0 && !solverFilter.has(e.solver_model ?? "")) return false;
      if (statusFilter.size > 0 && !statusFilter.has(STATUS_TO_FILTER_ID[e.status] ?? ""))
        return false;
      return true;
    });
    const dir = sortDir === "asc" ? 1 : -1;
    return [...matched].sort((a, b) => compare(a, b, sortKey) * dir);
  }, [exams, query, solverFilter, statusFilter, sortKey, sortDir]);

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));

  // Reset to the first page when the Title search (a prop) changes — adjusted
  // during render rather than in an effect. Sort changes reset the page in
  // `onSort`.
  const [prevQuery, setPrevQuery] = useState(query);
  if (query !== prevQuery) {
    setPrevQuery(query);
    setPage(1);
  }

  // Clamp during render so a shrinking result set can't strand us past the last
  // page; the stored `page` is corrected lazily rather than via an effect.
  const safePage = Math.min(page, totalPages);
  const pageStart = (safePage - 1) * PAGE_SIZE;
  const pageRows = filtered.slice(pageStart, pageStart + PAGE_SIZE);

  return (
    <div className="space-y-hestia-4">
      <div className="rounded-hestia-lg border border-hestia-border bg-hestia-surface">
        {/* Fixed widths on every column except Title (the flexible absorber,
            truncated via `max-w-0` on its cell). The Progress column keeps a
            fixed `w-[188px]` so parsing-bar vs. stepper content can't resize it —
            the parsing label truncates (min-w-0) so it never widens the column. */}
        <Table>
          <TableHeader>
            <TableRow>
              <SortableHead label="Title" columnKey="title" sortKey={sortKey} sortDir={sortDir} onSort={onSort} className="w-[30%]" />
              <FilterHead label="Status" ariaLabel="Filter by status" options={STATUS_OPTIONS} selected={statusFilter} onToggle={toggleStatus} onClear={clearStatus} className="w-[152px] text-center" />
              <SortableHead label="Progress" columnKey="progress" sortKey={sortKey} sortDir={sortDir} onSort={onSort} className="w-[188px]" />
              <FilterHead label="Solver" ariaLabel="Filter by solver model" options={SOLVER_OPTIONS} selected={solverFilter} onToggle={toggleSolver} onClear={clearSolver} className="w-[96px] text-center" />
              <SortableHead label="Created" columnKey="created" sortKey={sortKey} sortDir={sortDir} onSort={onSort} className="w-[104px]" />
              <TableHead className="w-[56px] text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {pageRows.length === 0 ? (
              <TableRow>
                <TableCell colSpan={6} className="h-24 text-center text-sm text-hestia-text-muted">
                  {query.trim().length > 0
                    ? `No exams match “${query}”.`
                    : "No exams match the selected filters."}
                </TableCell>
              </TableRow>
            ) : (
              pageRows.map((exam) => (
                <ExamTableRow key={exam.id} exam={exam} handlers={handlers} />
              ))
            )}
          </TableBody>
        </Table>
      </div>

      {totalPages > 1 && (
        <Pagination className="mx-0 justify-start">
          <PaginationContent>
            <PaginationItem>
              <PaginationPrevious
                href="#"
                onClick={(e) => {
                  e.preventDefault();
                  setPage(Math.max(1, safePage - 1));
                }}
                className={cn(safePage === 1 && "pointer-events-none opacity-50")}
              />
            </PaginationItem>
            {pageItems(safePage, totalPages).map((item, i) => (
              <PaginationItem key={`${item}-${i}`}>
                {item === "…" ? (
                  <span className="flex h-9 w-9 items-center justify-center text-hestia-text-muted">…</span>
                ) : (
                  <PaginationLink
                    href="#"
                    isActive={item === safePage}
                    onClick={(e) => {
                      e.preventDefault();
                      setPage(item);
                    }}
                  >
                    {item}
                  </PaginationLink>
                )}
              </PaginationItem>
            ))}
            <PaginationItem>
              <PaginationNext
                href="#"
                onClick={(e) => {
                  e.preventDefault();
                  setPage(Math.min(totalPages, safePage + 1));
                }}
                className={cn(safePage === totalPages && "pointer-events-none opacity-50")}
              />
            </PaginationItem>
          </PaginationContent>
        </Pagination>
      )}
    </div>
  );
};
