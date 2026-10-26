import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "../../api/client.ts";
import { createTopic } from "../../lib/createTopic.ts";
import type { MapCreation } from "../TopicMap.tsx";
import type { CreationState, CreationTier } from "./model.ts";

/**
 * The one goal being added anywhere in the tree: the open creation field, what is typed into it,
 * and the request that stores it. A topic is created on its own or with AI-written skills beneath
 * it; every lower tier is added as a child of the row it was opened under.
 */
export function useGoalCreation(courseId: number) {
  const queryClient = useQueryClient();
  const [creation, setCreation] = useState<CreationState | null>(null);
  const createMutation = useMutation({
    mutationFn: async (vars: CreationState) => {
      if (vars.tier === 1) {
        return createTopic(courseId, vars.text, vars.generate === true);
      }
      const result = await api.POST(
        "/api/courses/{courseId}/learning-goals/{goalId}/children",
        {
          params: { path: { courseId, goalId: vars.parentGoalId! } },
          // The tier of a childless node can't be read off its position, so it is stated.
          body: { text: vars.text, role: vars.tier === 4 ? "KNOWLEDGE" : "SKILL" },
        },
      );
      if (!result.data) {
        throw new Error(
          result.response.status === 409
            ? "Knowledge nodes cannot have children."
            : "Could not add the goal.",
        );
      }
      return result.data;
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["goals", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["course", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["courses"] });
    },
  });

  const begin = (tier: CreationTier, parentGoalId: number | null) => {
    createMutation.reset();
    setCreation({
      key: `${tier}:${parentGoalId ?? "root"}`,
      tier,
      parentGoalId,
      text: "",
    });
  };
  const cancel = () => {
    if (!createMutation.isPending) setCreation(null);
  };
  const updateText = (text: string) => {
    if (createMutation.isError) createMutation.reset();
    setCreation((current) => (current ? { ...current, text } : current));
  };
  const submit = (generate = false) => {
    if (!creation || creation.text.trim() === "") return;
    createMutation.mutate(
      { ...creation, text: creation.text.trim(), generate },
      { onSuccess: () => setCreation(null) },
    );
  };
  const error = createMutation.isError ? (createMutation.error as Error).message : undefined;

  // The same creation as the map inside an open topic sees it.
  const mapCreation: MapCreation = {
    activeKey: creation?.key ?? null,
    value: creation?.text ?? "",
    pending: createMutation.isPending,
    error,
    begin,
    change: updateText,
    submit,
    cancel,
  };

  return {
    creation,
    pending: createMutation.isPending,
    generating: createMutation.variables?.generate === true,
    error,
    begin,
    cancel,
    updateText,
    submit,
    close: () => setCreation(null),
    mapCreation,
  };
}
