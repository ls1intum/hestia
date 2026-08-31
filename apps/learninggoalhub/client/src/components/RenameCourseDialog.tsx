import { useEffect, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client.ts";
import type { CourseSummary } from "../api/client.ts";
import Button from "./Button.tsx";

/**
 * Renames a course. The course PATCH replaces `outputLanguage` and `figuresEnabled` with whatever
 * the body carries, so the rename echoes the course's current settings back rather than dropping
 * the instructor's language override on the way through.
 */
export default function RenameCourseDialog({
  course,
  onClose,
}: {
  course: CourseSummary;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [name, setName] = useState(course.name ?? "");

  const rename = useMutation({
    mutationFn: async (nextName: string) => {
      const { error } = await api.PATCH("/api/courses/{id}", {
        params: { path: { id: course.id as number } },
        body: {
          name: nextName,
          outputLanguage: course.outputLanguage ?? undefined,
          figuresEnabled: course.figuresEnabled,
        },
      });
      if (error) throw new Error("Could not rename the course.");
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["courses"] });
      await queryClient.invalidateQueries({ queryKey: ["course", course.id] });
      onClose();
    },
  });

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !rename.isPending) onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose, rename.isPending]);

  const trimmed = name.trim();
  const canSave = trimmed !== "" && trimmed !== course.name && !rename.isPending;

  return (
    <div
      onClick={() => !rename.isPending && onClose()}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="rename-course-title"
    >
      <form
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => {
          e.preventDefault();
          if (canSave) rename.mutate(trimmed);
        }}
        className="w-full max-w-sm rounded-xl border border-hestia-border bg-hestia-surface p-6 shadow-lg"
      >
        <h3 id="rename-course-title" className="text-lg text-hestia-text">
          Rename course
        </h3>
        <label
          htmlFor="rename-course-name"
          className="mt-4 block text-xs font-semibold uppercase tracking-wider text-hestia-text-muted"
        >
          Course title
        </label>
        <input
          id="rename-course-name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          autoFocus
          className="mt-2 w-full rounded-sm border-[1.5px] border-hestia-border bg-hestia-bg px-2.5 py-2 text-sm text-hestia-text transition focus:border-hestia-primary focus:shadow-[0_0_0_3px_var(--hestia-primary-muted)] focus:outline-none"
        />
        {rename.isError && (
          <p className="mt-3 text-sm text-hestia-danger">
            {(rename.error as Error).message}
          </p>
        )}
        <div className="mt-6 flex justify-end gap-2">
          <Button variant="neutral" onClick={onClose} disabled={rename.isPending}>
            Cancel
          </Button>
          <Button type="submit" disabled={!canSave}>
            {rename.isPending ? "Saving…" : "Save"}
          </Button>
        </div>
      </form>
    </div>
  );
}
