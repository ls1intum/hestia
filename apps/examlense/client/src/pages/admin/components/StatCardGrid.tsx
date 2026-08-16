import { Card, CardContent } from "@/components/ui/card";
import { cn } from "@/lib/utils/utils";

export interface Stat {
  label: string;
  value: string;
}

/**
 * Responsive grid of headline stat cards shared by the admin review panels.
 * `className` supplies the grid column layout (it differs per panel).
 */
export const StatCardGrid = ({
  stats,
  className,
}: {
  stats: Stat[];
  className?: string;
}) => (
  <div className={cn("grid gap-hestia-3", className)}>
    {stats.map((s) => (
      <Card key={s.label}>
        <CardContent className="p-hestia-4">
          <p className="text-xs font-medium text-hestia-text-muted">{s.label}</p>
          <p className="mt-1 font-body text-2xl font-bold tabular-nums text-hestia-text">
            {s.value}
          </p>
        </CardContent>
      </Card>
    ))}
  </div>
);
