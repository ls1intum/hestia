const BASE_URL = "/learninggoalhub/api";

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
  const res = await fetch(`${BASE_URL}/courses?size=100`);
  if (!res.ok) throw new Error("Failed to fetch courses");
  const data = await res.json();
  return data.content;
}

export async function fetchGoalsBySession(courseId: number): Promise<SessionGroup[]> {
  const res = await fetch(`${BASE_URL}/courses/${courseId}/learning-goals/by-session`);
  if (!res.ok) throw new Error("Failed to fetch learning goals");
  return await res.json();
}
