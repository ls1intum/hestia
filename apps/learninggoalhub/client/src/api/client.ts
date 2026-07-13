import createClient from "openapi-fetch";
import type { paths, components } from "./schema";

// The app is served under a base path (Vite `base`, e.g. "/learninggoalhub/"), which Vite
// exposes as import.meta.env.BASE_URL with a trailing slash. Strip the trailing slash so it
// composes cleanly with the absolute "/api/..." paths from the schema (openapi-fetch joins
// baseUrl + path) and with the raw fetch()/href URLs that bypass this client.
export const API_PREFIX = import.meta.env.BASE_URL.replace(/\/$/, "");

// Paths in the generated schema are absolute (/api/...); baseUrl prepends the app prefix so
// requests hit /<prefix>/api/... — same-origin, routed by the proxy to the server.
export const api = createClient<paths>({ baseUrl: API_PREFIX });

export type Schemas = components["schemas"];
export type CourseResponse = Schemas["CourseResponse"];
export type CourseSummary = Schemas["CourseSummaryResponse"];
export type DocumentResponse = Schemas["DocumentResponse"];
export type LearningGoal = Schemas["LearningGoalResponse"];
export type GoalSource = Schemas["GoalSourceResponse"];
export type GoalRelationship = Schemas["GoalRelationshipResponse"];
export type ExtractionSummary = Schemas["ExtractionSummary"];
export type ExtractionStatus = Schemas["Snapshot"];
