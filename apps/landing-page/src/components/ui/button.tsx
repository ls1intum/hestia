import { ButtonHTMLAttributes } from "react";

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: "primary" | "secondary";
  size?: "md" | "lg";
};

const base =
  "inline-flex items-center justify-center font-body font-semibold rounded-hestia-md transition-colors duration-150 disabled:opacity-60 disabled:pointer-events-none";

const variants = {
  primary: "bg-hestia-primary text-hestia-text-on-primary hover:bg-hestia-primary-hover",
  secondary:
    "bg-transparent text-hestia-primary border border-hestia-primary hover:bg-hestia-primary-muted",
};

const sizes = {
  md: "text-sm px-4 py-2",
  lg: "text-base px-6 py-2.5",
};

export function Button({ variant = "primary", size = "md", className = "", ...props }: ButtonProps) {
  return (
    <button
      className={`${base} ${variants[variant]} ${sizes[size]} ${className}`}
      {...props}
    />
  );
}
