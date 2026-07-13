import type { ReactNode } from "react";
import { Check, ExternalLink, Plus } from "lucide-react";

import { useLghCourses } from "@/hooks/use-learning-goals";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";

/** Sentinel selection value for "no course" (nothing picked → skippable). */
export const NO_COURSE = "none";
/** Sentinel selection value for "create a new course on confirm". */
export const CREATE_COURSE = "new";

interface Props {
  /** Current selection: NO_COURSE, CREATE_COURSE, or a stringified course id. */
  value: string;
  onChange: (value: string) => void;
  /** Title for the to-be-created course (only used when value === CREATE_COURSE). */
  newCourseName: string;
  onNewCourseNameChange: (value: string) => void;
}

/**
 * Shared step (both flows): optionally link a LearningGoalHub course so each
 * task gets learning goals when its section is confirmed. Pick an existing
 * course or create a new one (created in LGH and linked on confirm); leaving
 * nothing selected lets the wizard skip the step. Degrades gracefully when LGH
 * is unreachable.
 */
export const CoursePickerStep = ({
  value,
  onChange,
  newCourseName,
  onNewCourseNameChange,
}: Props) => {
  const { data: courses, isLoading, isError } = useLghCourses();

  return (
    <div className="space-y-hestia-3">
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

          <div className="max-h-[280px] overflow-y-auto">
            {isLoading ? (
              <div className="px-hestia-3 py-hestia-4 text-sm text-hestia-text-muted">
                Loading courses...
              </div>
            ) : (
              <>
                <CourseRow
                  selected={value === CREATE_COURSE}
                  name="Create new course"
                  icon={<Plus size={12} />}
                  onClick={() => onChange(CREATE_COURSE)}
                />
                {value === CREATE_COURSE && (
                  <div className="border-b border-hestia-border/70 bg-hestia-primary-muted/10 px-hestia-3 py-hestia-2">
                    <Input
                      autoFocus
                      value={newCourseName}
                      onChange={(e) => onNewCourseNameChange(e.target.value)}
                      placeholder="New course title"
                      aria-label="New course title"
                    />
                    <p className="mt-hestia-1 text-xs text-hestia-text-muted">
                      Created in LearningGoalHub and linked when you confirm.
                    </p>
                  </div>
                )}
                {(courses ?? []).map((course) => (
                  <CourseRow
                    key={course.id}
                    selected={value === String(course.id)}
                    name={course.name}
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
  icon,
  onClick,
}: {
  selected: boolean;
  name: string;
  icon?: ReactNode;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={onClick}
      className={cn(
        "flex min-h-[44px] w-full items-center gap-hestia-2 border-b border-hestia-border/70 px-hestia-3 py-hestia-2 text-left transition-colors last:border-b-0 hover:bg-hestia-primary-muted/20 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-hestia-primary",
        selected ? "bg-hestia-primary-muted/20" : "bg-transparent",
      )}
    >
      <span
        className={cn(
          "inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-hestia-full border transition-colors",
          selected
            ? "border-hestia-primary bg-hestia-primary text-white"
            : "border-hestia-border text-transparent",
        )}
        aria-hidden="true"
      >
        <Check size={12} />
      </span>
      {icon ? <span className="shrink-0 text-hestia-primary">{icon}</span> : null}
      <span className="min-w-0 flex-1 truncate text-sm font-medium text-hestia-text">
        {name}
      </span>
    </button>
  );
}
