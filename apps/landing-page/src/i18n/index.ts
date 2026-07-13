import { de, type Dictionary } from "./de";
import { en } from "./en";
import type { Language } from "@/hooks/use-language";

export type { Dictionary };

export const translations: Record<Language, Dictionary> = { de, en };
