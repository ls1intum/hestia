import type { Config } from "tailwindcss";

export default {
  darkMode: ["class", '[data-theme="dark"]'],
  content: ["./pages/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}", "./app/**/*.{ts,tsx}", "./src/**/*.{ts,tsx}"],
  prefix: "",
  theme: {
    container: {
      center: true,
      padding: "2rem",
      screens: { "2xl": "1400px" },
    },
    extend: {
      fontFamily: {
        display: ["var(--hestia-font-display)"],
        body: ["var(--hestia-font-body)"],
        sans: ["var(--hestia-font-body)"],
        mono: ["var(--hestia-font-mono)"],
      },
      colors: {
        border: "hsl(var(--border))",
        input: "hsl(var(--input))",
        ring: "hsl(var(--ring))",
        background: "hsl(var(--background))",
        foreground: "hsl(var(--foreground))",
        primary: {
          DEFAULT: "hsl(var(--primary))",
          foreground: "hsl(var(--primary-foreground))",
        },
        secondary: {
          DEFAULT: "hsl(var(--secondary))",
          foreground: "hsl(var(--secondary-foreground))",
        },
        destructive: {
          DEFAULT: "hsl(var(--destructive))",
          foreground: "hsl(var(--destructive-foreground))",
        },
        muted: {
          DEFAULT: "hsl(var(--muted))",
          foreground: "hsl(var(--muted-foreground))",
        },
        accent: {
          DEFAULT: "hsl(var(--accent))",
          foreground: "hsl(var(--accent-foreground))",
        },
        popover: {
          DEFAULT: "hsl(var(--popover))",
          foreground: "hsl(var(--popover-foreground))",
        },
        card: {
          DEFAULT: "hsl(var(--card))",
          foreground: "hsl(var(--card-foreground))",
        },
        hestia: {
          bg: "hsl(var(--hestia-bg))",
          surface: "hsl(var(--hestia-surface))",
          primary: "hsl(var(--hestia-primary))",
          "primary-hover": "hsl(var(--hestia-primary-hover))",
          "primary-muted": "hsl(var(--hestia-primary-muted))",
          text: "hsl(var(--hestia-text))",
          "text-muted": "hsl(var(--hestia-text-muted))",
          accent: "hsl(var(--hestia-accent))",
          warning: "hsl(var(--hestia-warning))",
          danger: "hsl(var(--hestia-danger))",
          success: "hsl(var(--hestia-success))",
          grading: "hsl(var(--hestia-grading))",
          border: "hsl(var(--hestia-border))",
          "border-strong": "hsl(var(--hestia-border-strong))",
        },
        sidebar: {
          DEFAULT: "hsl(var(--sidebar-background))",
          foreground: "hsl(var(--sidebar-foreground))",
          primary: "hsl(var(--sidebar-primary))",
          "primary-foreground": "hsl(var(--sidebar-primary-foreground))",
          accent: "hsl(var(--sidebar-accent))",
          "accent-foreground": "hsl(var(--sidebar-accent-foreground))",
          border: "hsl(var(--sidebar-border))",
          ring: "hsl(var(--sidebar-ring))",
        },
      },
      spacing: {
        "hestia-1": "var(--hestia-space-1)",
        "hestia-2": "var(--hestia-space-2)",
        "hestia-3": "var(--hestia-space-3)",
        "hestia-4": "var(--hestia-space-4)",
        "hestia-5": "var(--hestia-space-5)",
        "hestia-6": "var(--hestia-space-6)",
        "hestia-8": "var(--hestia-space-8)",
        "hestia-10": "var(--hestia-space-10)",
      },
      borderRadius: {
        lg: "var(--radius)",
        md: "calc(var(--radius) - 2px)",
        sm: "calc(var(--radius) - 4px)",
        "hestia-sm": "var(--hestia-radius-sm)",
        "hestia-md": "var(--hestia-radius-md)",
        "hestia-lg": "var(--hestia-radius-lg)",
        "hestia-xl": "var(--hestia-radius-xl)",
        "hestia-full": "var(--hestia-radius-full)",
      },
      boxShadow: {
        "hestia-sm": "var(--hestia-shadow-sm)",
        "hestia-md": "var(--hestia-shadow-md)",
        "hestia-lg": "var(--hestia-shadow-lg)",
      },
      keyframes: {
        "accordion-down": { from: { height: "0" }, to: { height: "var(--radix-accordion-content-height)" } },
        "accordion-up": { from: { height: "var(--radix-accordion-content-height)" }, to: { height: "0" } },
        // Like `ping` but scales to 1.12 instead of 2 — a tight halo
        // around the source element rather than a wide-reaching ripple.
        "ping-sm": {
          "75%, 100%": { transform: "scale(1.35)", opacity: "0" },
        },
        // One-shot shimmer streak across a progress fill: a 50%-wide
        // translucent gradient that translates from off-left to off-right
        // exactly once on grade-commit feedback.
        "progress-shimmer": {
          "0%":   { transform: "translateX(-100%)" },
          "100%": { transform: "translateX(200%)" },
        },
        // Expanding, fading danger-colored halo — draws attention to an
        // unset score input without moving or fading the field itself.
        "pulse-danger": {
          "0%":   { boxShadow: "0 0 0 0 hsl(var(--hestia-danger) / 0.4)" },
          "70%":  { boxShadow: "0 0 0 5px hsl(var(--hestia-danger) / 0)" },
          "100%": { boxShadow: "0 0 0 0 hsl(var(--hestia-danger) / 0)" },
        },
      },
      animation: {
        "accordion-down": "accordion-down 0.2s ease-out",
        "accordion-up": "accordion-up 0.2s ease-out",
        "ping-sm": "ping-sm 1.5s cubic-bezier(0, 0, 0.2, 1) infinite",
        "progress-shimmer": "progress-shimmer 0.8s ease-out",
        // Looping variant for in-progress loaders (e.g. the parsing bar).
        "progress-shimmer-loop": "progress-shimmer 1.6s ease-in-out infinite",
        "pulse-danger": "pulse-danger 1.8s ease-out infinite",
      },
    },
  },
  plugins: [require("tailwindcss-animate")],
} satisfies Config;
