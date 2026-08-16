import React, { useState } from "react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import { WorkshopInput } from "@/lib/workshop-generator";
import { ACTIVITY_GROUPS } from "@/lib/constants";
import { CheckSquare, Info, X, ArrowLeft, ArrowRight, ChevronDown, ChevronRight } from "lucide-react";

interface Props {
  initialInput: Partial<WorkshopInput>;
  onNext: (activities: string[]) => void;
  isLoading?: boolean;
  onBack?: () => void;
  /** N-1: called on every selection change so parent can persist state across re-mounts */
  onSelectionsChange?: (activities: string[], materials: string[]) => void;
}

// Flat list of all known activity names for custom activity detection
const ALL_KNOWN_ACTIVITIES = ACTIVITY_GROUPS.flatMap(g => g.activities.map(a => a.name));

export default function WorkshopFormStep2({ initialInput, onNext, isLoading = false, onBack, onSelectionsChange }: Props) {
  const [selectedActivities, setSelectedActivities] = useState<string[]>(initialInput?.selectedActivities ?? []);
  const [customActivity, setCustomActivity] = useState("");
  // All groups collapsed by default; track which are expanded
  const [expandedGroups, setExpandedGroups] = useState<Record<string, boolean>>({});

  const handleNext = () => {
    onNext(selectedActivities);
  };

  const toggleActivity = (act: string) => {
    setSelectedActivities(prev => {
      const next = prev.includes(act) ? prev.filter(a => a !== act) : [...prev, act];
      onSelectionsChange?.(next, initialInput?.availableMaterials ?? []);
      return next;
    });
  };

  const addCustomActivity = () => {
    const v = customActivity.trim();
    if (v && !selectedActivities.includes(v)) {
      setSelectedActivities(prev => {
        const next = [...prev, v];
        onSelectionsChange?.(next, initialInput?.availableMaterials ?? []);
        return next;
      });
      setCustomActivity("");
    }
  };

  const toggleGroup = (label: string) => {
    setExpandedGroups(prev => ({ ...prev, [label]: !prev[label] }));
  };

  // Custom activities are those not in any group
  const customActivities = selectedActivities.filter(a => !ALL_KNOWN_ACTIVITIES.includes(a));

  return (
    <div className="space-y-2">
      <Card className="border-border/60 shadow-lg">
        <CardHeader>
          <CardTitle className="font-display text-3xl">Activities</CardTitle>
          <CardDescription>
            Pick the activities you'd like incorporated into the workshop. Activities are grouped by participation structure.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="space-y-6">
            {/* Grouped Activities */}
              <TooltipProvider delayDuration={250}>
                <div className="space-y-2">
                  {ACTIVITY_GROUPS.map(group => {
                    const isExpanded = !!expandedGroups[group.label];
                    const selectedCount = group.activities.filter(a => selectedActivities.includes(a.name)).length;
                    return (
                      <div key={group.label}>
                        {/* Clickable group header */}
                        <button
                          type="button"
                          onClick={() => toggleGroup(group.label)}
                          className="flex items-center gap-2 w-full text-left mb-1 group"
                        >
                          <span className="text-base">{group.groupEmoji}</span>
                          <span className="text-xs font-mono font-semibold uppercase tracking-widest text-muted-foreground group-hover:text-foreground transition-colors">
                            {group.label}
                          </span>
                          {selectedCount > 0 && (
                            <span className="inline-flex items-center justify-center h-4 min-w-4 px-1 rounded-full text-[10px] font-bold bg-primary/15 text-primary">
                              {selectedCount}
                            </span>
                          )}
                          <div className="flex-1 h-px bg-border/50" />
                          {isExpanded
                            ? <ChevronDown className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                            : <ChevronRight className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                          }
                        </button>

                        {/* Collapsible activities grid */}
                        {isExpanded && (
                          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 pl-6 mb-2">
                            {group.activities.map(act => (
                              <div key={act.name} className="flex items-center gap-1">
                                <button
                                  type="button"
                                  disabled={isLoading}
                                  onClick={() => toggleActivity(act.name)}
                                  className={`flex-1 flex items-center gap-2 rounded-md border px-3 py-2 text-left transition-all text-sm ${
                                    selectedActivities.includes(act.name)
                                      ? "border-primary bg-primary/10 text-primary"
                                      : "border-border/60 bg-card hover:bg-muted/40"
                                  }`}
                                >
                                  <CheckSquare
                                    className={`h-4 w-4 shrink-0 ${selectedActivities.includes(act.name) ? "opacity-100" : "opacity-30"}`}
                                  />
                                  <span className="truncate">{act.emoji} {act.name}</span>
                                </button>
                                <Tooltip>
                                  <TooltipTrigger asChild>
                                    <Button
                                      type="button"
                                      variant="ghost"
                                      size="icon"
                                      className="h-8 w-8 shrink-0 text-muted-foreground hover:text-foreground"
                                    >
                                      <Info className="h-4 w-4" />
                                    </Button>
                                  </TooltipTrigger>
                                  <TooltipContent side="right" className="max-w-xs text-sm">
                                    <p>{act.desc}</p>
                                  </TooltipContent>
                                </Tooltip>
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    );
                  })}

                  {/* Custom activities (user-added ones not in any group) */}
                  {customActivities.length > 0 && (
                    <div>
                      <div className="flex items-center gap-2 mb-2">
                        <span className="text-base">✨</span>
                        <span className="text-xs font-mono font-semibold uppercase tracking-widest text-muted-foreground">
                          Custom
                        </span>
                        <div className="flex-1 h-px bg-border/50" />
                      </div>
                      <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 pl-6">
                        {customActivities.map(act => (
                          <div key={act} className="flex items-center gap-1">
                            <button
                              type="button"
                              disabled={isLoading}
                              onClick={() => toggleActivity(act)}
                              className="flex-1 flex items-center gap-2 rounded-md border px-3 py-2 text-left transition-all text-sm border-primary bg-primary/10 text-primary"
                            >
                              <CheckSquare className="h-4 w-4 shrink-0 opacity-100" />
                              <span className="truncate">{act}</span>
                            </button>
                            <Button
                              type="button"
                              variant="ghost"
                              size="icon"
                              className="h-8 w-8 shrink-0 text-muted-foreground hover:text-destructive"
                              onClick={() => toggleActivity(act)}
                            >
                              <X className="h-4 w-4" />
                            </Button>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* Add custom activity — input is flex-1, Add button aligns with info icons */}
                  <div className="pt-2">
                    <div className="h-px bg-border/50 w-full mb-3" />
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 pl-6">
                      <div className="flex items-center gap-1">
                        <Input
                          placeholder="Add custom activity…"
                          value={customActivity}
                          onChange={(e) => setCustomActivity(e.target.value)}
                          onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); addCustomActivity(); } }}
                          disabled={isLoading}
                          className="flex-1 min-w-0 h-9 text-sm"
                        />
                        <Button
                          type="button"
                          variant="outline"
                          onClick={addCustomActivity}
                          disabled={isLoading || !customActivity.trim()}
                          className="h-8 w-8 shrink-0 p-0 text-xs font-semibold"
                          title="Add custom activity"
                        >
                          +
                        </Button>
                      </div>
                    </div>
                  </div>
                </div>
              </TooltipProvider>

            {/* Navigation */}
            <div className="flex justify-between pt-2">
              {onBack ? (
                <Button type="button" variant="outline" onClick={onBack} disabled={isLoading} className="gap-2 font-body">
                  <ArrowLeft className="h-4 w-4" /> Previous step
                </Button>
              ) : <div />}
              <Button
                type="button"
                size="lg"
                className="px-10 font-body font-semibold text-base gap-2"
                onClick={handleNext}
                disabled={isLoading}
              >
                Next step <ArrowRight className="h-4 w-4" />
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
