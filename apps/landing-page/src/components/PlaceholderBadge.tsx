// Visible marker for integrations that aren't wired up yet (Listmonk, Nextcloud,
// Impressum, …). Remove the badge at the call site once the real endpoint lands.
export function PlaceholderBadge({ label }: { label?: string }) {
  return (
    <span className="inline-flex items-center gap-1 rounded-full border border-dashed border-hestia-border-strong px-2 py-px align-middle font-mono text-[10px] text-hestia-text-muted">
      {label ? `Platzhalter · ${label}` : "Platzhalter"}
    </span>
  );
}
