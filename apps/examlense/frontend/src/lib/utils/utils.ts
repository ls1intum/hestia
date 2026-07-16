import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";
import type { WheelEvent } from "react";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/**
 * Blur a number input on wheel scroll so scrolling the page doesn't silently
 * change its value. Wire to `onWheel` on `<input type="number">`.
 */
export const preventNumberWheelChange = (event: WheelEvent<HTMLInputElement>) => {
  event.currentTarget.blur();
};
