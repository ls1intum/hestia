import type { TreeNode } from "../../lib/goals.ts";

/**
 * What a topic puts up for review: its skills, or — for a topic the structuring left without skills,
 * which would otherwise show nothing — the sub-skills directly beneath it.
 */
export function reviewItemsOf(topic: TreeNode): TreeNode[] {
  const skills = topic.children.filter((child) => child.role === "skill");
  return skills.length > 0 ? skills : topic.children.filter((child) => child.role === "subSkill");
}
