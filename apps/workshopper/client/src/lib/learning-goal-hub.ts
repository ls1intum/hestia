import { getFromLearningGoalHub } from "./api";

export interface Course {
  id: number;
  name: string;
}

export interface GoalRelationship {
  type: string;
  targetGoalId: number;
  targetText: string;
  confidence: number;
  origin: string;
}

export interface LearningGoal {
  id: number;
  text: string;
  kind: string;
  role: string;
  origin?: string;
  status: string;
  bloomLevel?: string;
  soloLevel?: string;
  lectureOrder?: number;
  hierarchy?: {
    module?: string;
    session?: string;
    exercise?: string;
  };
  relationships?: GoalRelationship[];
}

export interface SessionGroup {
  nodeId: number | null;
  level: string | null;
  label: string | null;
  goals: LearningGoal[];
}

export async function fetchCourses(): Promise<Course[]> {
  const data = await getFromLearningGoalHub<{ content: Course[] }>("/courses?size=100");
  return data.content;
}

export function fetchGoalsBySession(courseId: number): Promise<SessionGroup[]> {
  return getFromLearningGoalHub<SessionGroup[]>(`/courses/${courseId}/learning-goals/by-session`);
}
