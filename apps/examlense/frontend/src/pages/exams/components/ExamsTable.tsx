import { useMemo, useState } from "react";
import { ArrowDown, ArrowUp, ChevronsUpDown } from "lucide-react";
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
import { cn } from "@/lib/utils/utils";
import { ExamTableRow, type ExamRowHandlers } from "./ExamTableRow";

const PAGE_SIZE = 10;

type SortKey = "title" | "status" | "progress" | "created";
type SortDir = "asc" | "desc";

/** Default direction when a column is first selected. */
const DEFAULT_DIR: Record<SortKey, SortDir> = {
  title: "asc",
  status: "asc",
  progress: "desc",
  created: "desc",
};

const compare = (a: ExamListItem, b: ExamListItem, key: SortKey): number => {
  switch (key) {
    case "title":
      return (a.title || "Untitled exam").localeCompare(b.title || "Untitled exam");
    case "status":
      return EXAM_STATUS_META[a.status].rank - EXAM_STATUS_META[b.status].rank;
    case "progress":
      return progressSortValue(a) - progressSortValue(b);
    case "created":
      return new Date(a.created_at).getTime() - new Date(b.created_at).getTime();
  }
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

  const filtered = useMemo(() => {
    const matched =
      query.trim().length === 0
        ? exams
        : exams.filter((e) => fuzzyMatch(query, e.title || "Untitled exam") !== null);
    const dir = sortDir === "asc" ? 1 : -1;
    return [...matched].sort((a, b) => compare(a, b, sortKey) * dir);
  }, [exams, query, sortKey, sortDir]);

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
        <Table>
          <TableHeader>
            <TableRow>
              <SortableHead label="Title" columnKey="title" sortKey={sortKey} sortDir={sortDir} onSort={onSort} className="w-[44%]" />
              <SortableHead label="Status" columnKey="status" sortKey={sortKey} sortDir={sortDir} onSort={onSort} />
              <SortableHead label="Progress" columnKey="progress" sortKey={sortKey} sortDir={sortDir} onSort={onSort} />
              <TableHead>Solver</TableHead>
              <SortableHead label="Created" columnKey="created" sortKey={sortKey} sortDir={sortDir} onSort={onSort} className="w-[104px]" />
              <TableHead className="w-[56px] text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {pageRows.length === 0 ? (
              <TableRow>
                <TableCell colSpan={6} className="h-24 text-center text-sm text-hestia-text-muted">
                  No exams match “{query}”.
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
