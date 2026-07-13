import type { Config } from "tailwindcss";

// Maps the canonical HESTIA design tokens (CSS variables defined in src/index.css)
// into Tailwind utilities. Derived states (hover/muted/border) come from color-mix
// in the CSS layer — never invent new hex values here.
export default {
  darkMode: ["class", '[data-theme="dark"]'],
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        hestia: {
          bg: "var(--hestia-bg)",
          surface: "var(--hestia-surface)",
          primary: "var(--hestia-primary)",
          "primary-hover": "var(--hestia-primary-hover)",
          "primary-muted": "var(--hestia-primary-muted)",
          text: "var(--hestia-text)",
          "text-muted": "var(--hestia-text-muted)",
          "text-on-primary": "var(--hestia-text-on-primary)",
          accent: "var(--hestia-accent)",
          "accent-hover": "var(--hestia-accent-hover)",
          "accent-muted": "var(--hestia-accent-muted)",
          warning: "var(--hestia-warning)",
          danger: "var(--hestia-danger)",
          "danger-hover": "var(--hestia-danger-hover)",
          border: "var(--hestia-border)",
          "border-strong": "var(--hestia-border-strong)",
        },
      },
      fontFamily: {
        display: "var(--hestia-font-display)",
        body: "var(--hestia-font-body)",
        mono: "var(--hestia-font-mono)",
      },
      borderRadius: {
        "hestia-sm": "var(--hestia-radius-sm)",
        "hestia-md": "var(--hestia-radius-md)",
        "hestia-lg": "var(--hestia-radius-lg)",
        "hestia-xl": "var(--hestia-radius-xl)",
      },
      boxShadow: {
        "hestia-sm": "var(--hestia-shadow-sm)",
        "hestia-md": "var(--hestia-shadow-md)",
        "hestia-lg": "var(--hestia-shadow-lg)",
      },
      maxWidth: {
        page: "1080px",
      },
    },
  },
  plugins: [],
} satisfies Config;
