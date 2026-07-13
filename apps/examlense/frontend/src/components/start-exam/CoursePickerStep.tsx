import { Check, ExternalLink } from "lucide-react";

import { useLghCourses } from "@/hooks/use-learning-goals";
import { cn } from "@/lib/utils";

/** Sentinel Select value for "no course" — Radix Select values must be non-empty. */
export const NO_COURSE = "none";

interface Props {
  /** Current selection: NO_COURSE or a stringified course id. */
  value: string;
  onChange: (value: string) => void;
}

/**
 * Shared step (both flows): optionally link a LearningGoalHub course so
 * each task gets learning goals when its section is confirmed. Skippable via
 * the "No course" option. Degrades gracefully when LGH is unreachable.
 */
export const CoursePickerStep = ({ value, onChange }: Props) => {
  const { data: courses, isLoading, isError } = useLghCourses();

  return (
    <div className="space-y-hestia-3">
      <p className="text-sm text-hestia-text-muted">
        Link a LearningGoalHub course to derive learning goals for each task
        automatically when you confirm its section. You can also continue
        without a course — the exam then has no learning-goal insights.
      </p>

      {isError ? (
        <p className="rounded-hestia-md border border-hestia-border bg-hestia-primary-muted/10 px-hestia-3 py-hestia-2 text-sm text-hestia-text-muted">
          LearningGoalHub is unreachable — you can continue without a course.
        </p>
      ) : (
        <div className="overflow-hidden rounded-hestia-md border border-hestia-primary/25 bg-hestia-surface shadow-[0_16px_40px_rgba(15,23,42,0.14)] dark:shadow-[0_18px_44px_rgba(0,0,0,0.42)]">
          <div className="flex items-center justify-between gap-hestia-3 border-b border-hestia-border bg-hestia-bg/80 px-hestia-3 py-hestia-2">
            <div className="flex min-w-0 items-center gap-hestia-2">
              <span className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-hestia-md border border-hestia-primary/25 bg-hestia-primary-muted/20 text-hestia-primary">
                <ExternalLink size={14} />
              </span>
              <span className="min-w-0">
                <span className="block text-sm font-semibold text-hestia-text">
                  LearningGoalHub
                </span>
                <span className="block truncate text-xs text-hestia-text-muted">
                  External course list
                </span>
              </span>
            </div>
          </div>
          <div className="grid grid-cols-[44px_minmax(0,1fr)_80px] border-b border-hestia-border bg-hestia-bg/60 px-hestia-3 py-hestia-2 text-[11px] font-semibold uppercase tracking-wide text-hestia-text-muted">
            <span />
            <span>Course</span>
            <span className="text-right">ID</span>
          </div>

          <div className="max-h-[280px] overflow-y-auto">
            {isLoading ? (
              <div className="px-hestia-3 py-hestia-4 text-sm text-hestia-text-muted">
                Loading courses...
              </div>
            ) : (
              <>
                <CourseRow
                  selected={value === NO_COURSE}
                  name="No course"
                  idLabel="Skip"
                  onClick={() => onChange(NO_COURSE)}
                />
                {(courses ?? []).map((course) => (
                  <CourseRow
                    key={course.id}
                    selected={value === String(course.id)}
                    name={course.name}
                    idLabel={String(course.id)}
                    onClick={() => onChange(String(course.id))}
                  />
                ))}
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

function CourseRow({
  selected,
  name,
  idLabel,
  onClick,
}: {
  selected: boolean;
  name: string;
  idLabel: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={onClick}
      className={cn(
        "grid min-h-[44px] w-full grid-cols-[44px_minmax(0,1fr)_80px] items-center border-b border-hestia-border/70 px-hestia-3 py-hestia-2 text-left transition-colors last:border-b-0 hover:bg-hestia-primary-muted/20 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-hestia-primary",
        selected ? "bg-hestia-primary-muted/20" : "bg-transparent",
      )}
    >
      <span
        className={cn(
          "inline-flex h-5 w-5 items-center justify-center rounded-hestia-full border transition-colors",
          selected
            ? "border-hestia-primary bg-hestia-primary text-white"
            : "border-hestia-border text-transparent",
        )}
        aria-hidden="true"
      >
        <Check size={12} />
      </span>
      <span className="min-w-0 truncate text-sm font-medium text-hestia-text">
        {name}
      </span>
      <span className="text-right text-xs font-medium text-hestia-text-muted">
        {idLabel}
      </span>
    </button>
  );
}
