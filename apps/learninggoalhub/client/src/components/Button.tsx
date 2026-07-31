import type { ButtonHTMLAttributes } from "react";

/**
 * Shared button surface, so the HESTIA button recipes live in one place instead of being
 * re-typed per call site. `neutral` is not in the styleguide: it is the quiet outline used for
 * cancel/dismiss actions, which would pull far too much attention as gold `secondary`.
 */
type ButtonVariant = "primary" | "secondary" | "neutral" | "danger" | "ghost";

/** `icon-*` are square sizes for icon-only buttons; the rest carry a text label. */
type ButtonSize = "sm" | "md" | "lg" | "icon-sm" | "icon-md";

const BASE =
  "inline-flex items-center justify-center gap-1.5 rounded-md transition disabled:cursor-not-allowed disabled:opacity-50";

const VARIANTS: Record<ButtonVariant, string> = {
  // `on-primary`/`on-danger` flip with the theme — the dark palette's primary is a light gold,
  // where white text would sit at ~1.9:1. Never hardcode a white foreground on a filled surface.
  primary:
    "bg-hestia-primary text-hestia-on-primary font-semibold hover:bg-hestia-primary-hover",
  secondary:
    "border-[1.5px] border-hestia-primary text-hestia-primary font-semibold hover:bg-hestia-primary-muted",
  neutral:
    "border border-hestia-border text-hestia-text font-medium hover:bg-hestia-primary-muted",
  danger: "bg-hestia-danger text-hestia-on-danger font-semibold hover:opacity-90",
  ghost: "text-hestia-text-muted font-medium hover:bg-hestia-primary-muted hover:text-hestia-text",
};

const SIZES: Record<ButtonSize, string> = {
  sm: "px-2.5 py-1 text-xs",
  md: "px-3 py-1.5 text-sm",
  lg: "px-4 py-2 text-sm",
  "icon-sm": "h-8 w-8 shrink-0",
  "icon-md": "h-9 w-9 shrink-0",
};

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant;
  size?: ButtonSize;
};

export default function Button({
  variant = "primary",
  size = "md",
  className = "",
  type = "button",
  ...props
}: ButtonProps) {
  return (
    <button
      type={type}
      className={`${BASE} ${VARIANTS[variant]} ${SIZES[size]} ${className}`.trim()}
      {...props}
    />
  );
}
