import { createContext, useContext } from "react";

/** "system" follows the OS preference; "light"/"dark" pin the theme via the data-theme attribute. */
export type ThemePreference = "light" | "dark" | "system";
/** The theme actually rendered after resolving "system" against the OS preference. */
export type ResolvedTheme = "light" | "dark";

export interface ThemeContextValue {
  preference: ThemePreference;
  resolved: ResolvedTheme;
  setPreference: (preference: ThemePreference) => void;
  toggle: () => void;
}

export const ThemeContext = createContext<ThemeContextValue | null>(null);

export function useTheme(): ThemeContextValue {
  const context = useContext(ThemeContext);
  if (!context) {
    throw new Error("useTheme must be used within a ThemeProvider");
  }
  return context;
}
