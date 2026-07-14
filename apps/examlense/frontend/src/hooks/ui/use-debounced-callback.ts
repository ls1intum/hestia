import { useEffect, useMemo, useRef } from "react";

export function useDebouncedCallback<T extends (...args: any[]) => void>(
  fn: T,
  delay = 400
) {
  const fnRef = useRef(fn);
  useEffect(() => {
    fnRef.current = fn;
  }, [fn]);

  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const debounced = useMemo(() => {
    const wrapper = (...args: Parameters<T>) => {
      if (timer.current) clearTimeout(timer.current);
      timer.current = setTimeout(() => fnRef.current(...args), delay);
    };
    wrapper.flush = () => {
      if (timer.current) {
        clearTimeout(timer.current);
        timer.current = null;
      }
    };
    return wrapper;
  }, [delay]);

  useEffect(() => () => debounced.flush(), [debounced]);

  return debounced;
}