/** Single-user mode: no auth gate. Kept as a passthrough so routes don't change. */
export const RequireAuth = ({ children }: { children: React.ReactNode }) => {
  return <>{children}</>;
};
