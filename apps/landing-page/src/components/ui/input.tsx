import { InputHTMLAttributes } from "react";

type InputProps = InputHTMLAttributes<HTMLInputElement>;

export function Input({ className = "", ...props }: InputProps) {
  return (
    <input
      className={`w-full rounded-hestia-sm border border-hestia-border-strong bg-hestia-surface px-3 py-2 text-base text-hestia-text placeholder:text-hestia-text-muted focus:border-hestia-primary ${className}`}
      {...props}
    />
  );
}
