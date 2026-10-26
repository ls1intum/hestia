import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, type ExtractionStatus } from "../../api/client.ts";
import Button from "../Button.tsx";

/**
 * A failed run: what failed, and a retry. A run that failed only while building the tree, with its
 * goals already saved, retries just the tree.
 */
export function RunFailure({
  status,
  error,
  courseId,
  onClose,
}: {
  status?: ExtractionStatus;
  error?: string | null;
  courseId?: number | null;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const treeOnlyRetry = status?.status === "FAILED"
    && status.phase === "SYNTHESIZING"
    && (status.summary?.goalsCreated ?? 0) > 0
    && (status.failedSessions ?? 0) === 0;

  const retryMutation = useMutation({
    mutationFn: async () => {
      if (courseId == null) throw new Error("Course is unavailable.");
      const result = treeOnlyRetry
        ? await api.POST("/api/courses/{courseId}/competency-tree", {
            params: { path: { courseId }, query: { force: false } },
          })
        : await api.POST("/api/courses/{courseId}/extract", {
            params: { path: { courseId }, query: { force: true } },
          });
      if (result.error || !result.data) {
        throw new Error(
          result.response.status === 409
            ? "Another extraction or tree rebuild is already running. Please wait."
            : treeOnlyRetry
              ? "Could not rebuild the Topic tree. The extracted goals are still saved."
              : "Could not restart the extraction.",
      );
    }
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["extract-status", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["extraction-current"] });
      await queryClient.invalidateQueries({ queryKey: ["course", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["courses"] });
      await queryClient.invalidateQueries({ queryKey: ["goals", courseId] });
    },
  });
  const failedSessions = status?.failedSessions ?? 0;
  const failedSessionNames = status?.failedSessionNames ?? [];

  return (
    <div className="flex flex-col gap-4 rounded-xl border border-hestia-border bg-hestia-surface p-4 shadow-sm">
      <div className="rounded-lg bg-[color-mix(in_srgb,var(--hestia-danger)_12%,var(--hestia-surface))] px-3 py-3 text-sm text-hestia-danger">
        <p className="font-medium">
          {failedSessions > 0
            ? failedSessions === 1
              ? "One session could not be analysed. No partial Topic tree was saved."
              : `${failedSessions} sessions could not be analysed. No partial Topic tree was saved.`
            : error ?? "Extraction failed."}
        </p>
        {failedSessionNames.length > 0 && (
          <ul className="mt-2 list-disc space-y-1 pl-5 text-xs">
            {failedSessionNames.map((name) => <li key={name}>{name}</li>)}
          </ul>
        )}
      </div>
      {retryMutation.isError && (
        <p className="text-sm text-hestia-danger">
          {(retryMutation.error as Error).message}
        </p>
      )}
      <div className="flex justify-end gap-2">
        <Button variant="neutral" className="h-9" onClick={onClose}>
          Close
        </Button>
        <Button
          className="h-9"
          onClick={() => retryMutation.mutate()}
          disabled={retryMutation.isPending || courseId == null}
        >
          {retryMutation.isPending
            ? treeOnlyRetry ? "Rebuilding tree…" : "Retrying…"
            : treeOnlyRetry ? "Retry Topic tree" : "Retry extraction"}
        </Button>
      </div>
    </div>
  );
}
