import { useState } from "react";
import { ArrowRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { WorkshopInput, SessionType, InteractionLevel } from "@/lib/workshop-generator";

interface Props {
  onNext: (input: Partial<WorkshopInput>) => void;
  isLoading?: boolean;
  initialInput?: Partial<WorkshopInput>;
  entityType?: "SESSION" | "LECTURE";
}

const INTERACTION_LEVELS: { value: InteractionLevel; label: string; desc: string }[] = [
  { value: "minimal", label: "Minimal", desc: "Q&A, polls, think-share" },
  { value: "moderate", label: "Moderate", desc: "Pair work, small groups" },
  { value: "high", label: "High", desc: "Debates, peer teaching, projects" },
];

export default function WorkshopFormStep1({ onNext, isLoading = false, initialInput, entityType = "SESSION" }: Props) {
  const [title, setTitle] = useState(initialInput?.title ?? "");
  const [duration, setDuration] = useState<number | "">(initialInput?.duration ?? 90);
  const [participants, setParticipants] = useState<number | "">(initialInput?.participants ?? 15);
  const [sessionType, setSessionType] = useState<SessionType>((initialInput?.sessionType as SessionType) ?? "lecture");
  const [sessionTypeOther, setSessionTypeOther] = useState(initialInput?.sessionTypeOther ?? "");
  const [studentBackground, setStudentBackground] = useState(initialInput?.studentBackground ?? "");
  const [interactionLevel, setInteractionLevel] = useState<InteractionLevel>((initialInput?.interactionLevel as InteractionLevel) ?? "moderate");

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const finalDuration = Math.max(0, Math.min(480, Number(duration) || 0));
    const finalParticipants = Math.max(0, Math.min(500, Number(participants) || 0));
    setDuration(finalDuration);
    setParticipants(finalParticipants);
    onNext({
      title: entityType === "LECTURE" ? title.trim() || undefined : undefined,
      duration: finalDuration,
      participants: finalParticipants,
      sessionType,
      sessionTypeOther: sessionType === "other" ? sessionTypeOther.trim() || undefined : undefined,
      studentBackground: studentBackground.trim() || undefined,
      interactionLevel,
    });
  };

  const canSubmit = !isLoading && Number(duration) >= 0 && (entityType === "SESSION" || title.trim() !== "");

  return (
    <Card className="border-border/60 shadow-lg">
      <CardHeader>
        <CardTitle className="font-display text-3xl">Step 1: Session Setup</CardTitle>
        <CardDescription>
          Configure the basic parameters for your session.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={handleSubmit} className="space-y-6">
          {entityType === "LECTURE" && (
            <div className="space-y-2">
              <Label htmlFor="title" className="font-body font-medium">Lecture Title</Label>
              <Input
                id="title"
                placeholder="e.g. Introduction to Machine Learning"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                disabled={isLoading}
                required
              />
            </div>
          )}

          {/* Session Type */}
          <div className="space-y-2">
            <Label htmlFor="sessionType" className="font-body font-medium">Session Type</Label>
            <Select value={sessionType} onValueChange={(v) => setSessionType(v as SessionType)} disabled={isLoading}>
              <SelectTrigger id="sessionType">
                <SelectValue placeholder="Select a session type" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="lecture">Lecture</SelectItem>
                <SelectItem value="workshop">Workshop</SelectItem>
                <SelectItem value="exercise">Exercise session</SelectItem>
                <SelectItem value="seminar">Seminar</SelectItem>
                <SelectItem value="practical">Practical course</SelectItem>
                <SelectItem value="other">Other…</SelectItem>
              </SelectContent>
            </Select>
            {sessionType === "other" && (
              <Input
                placeholder="Specify the session type"
                value={sessionTypeOther}
                onChange={(e) => setSessionTypeOther(e.target.value)}
                maxLength={100}
                disabled={isLoading}
                className="mt-2"
              />
            )}
          </div>

          {/* Duration + Participants */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="duration" className="font-body font-medium">Duration (minutes)</Label>
              <Input
                id="duration"
                type="number"
                min={0}
                max={480}
                step={5}
                value={duration}
                onChange={(e) => setDuration(e.target.value === "" ? "" : Number(e.target.value))}
                onBlur={(e) => {
                  const n = Number(e.target.value);
                  setDuration(!e.target.value || isNaN(n) ? 0 : Math.max(0, Math.min(480, n)));
                }}
                required
                disabled={isLoading}
                className="no-spinner"
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="participants" className="font-body font-medium">Participants</Label>
              <Input
                id="participants"
                type="number"
                min={0}
                max={500}
                step={1}
                value={participants}
                onChange={(e) => setParticipants(e.target.value === "" ? "" : Number(e.target.value))}
                onBlur={(e) => {
                  const n = Number(e.target.value);
                  setParticipants(!e.target.value || isNaN(n) ? 0 : Math.max(0, Math.min(500, n)));
                }}
                required
                disabled={isLoading}
                className="no-spinner"
              />
            </div>
          </div>

          {/* Student Background */}
          <div className="space-y-2">
            <Label htmlFor="background" className="font-body font-medium">
              Student Background <span className="text-muted-foreground font-normal">(optional)</span>
            </Label>
            <Input
              id="background"
              placeholder="e.g. Bachelor's students, Master's students, mixed, professionals…"
              value={studentBackground}
              onChange={(e) => setStudentBackground(e.target.value)}
              maxLength={200}
              disabled={isLoading}
            />
          </div>

          {/* Interaction Level */}
          <div className="space-y-2">
            <Label className="font-body font-medium">Student Interaction Level</Label>
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
              {INTERACTION_LEVELS.map((lvl) => (
                <button
                  key={lvl.value}
                  type="button"
                  disabled={isLoading}
                  onClick={() => setInteractionLevel(lvl.value)}
                  className={`rounded-md border px-3 py-2 text-left transition-all ${interactionLevel === lvl.value
                    ? "border-primary bg-primary/10 ring-1 ring-primary"
                    : "border-border/60 bg-card hover:bg-muted/40"
                    }`}
                >
                  <p className={`text-sm font-semibold font-body ${interactionLevel === lvl.value ? "text-primary" : "text-foreground"
                    }`}>{lvl.label}</p>
                  <p className="text-xs text-muted-foreground font-body mt-0.5">{lvl.desc}</p>
                </button>
              ))}
            </div>
          </div>

          <div className="flex justify-end pt-2">
            <Button type="submit" size="lg" className="px-10 font-body font-semibold text-base gap-2" disabled={!canSubmit}>
              Next step <ArrowRight className="h-4 w-4" />
            </Button>
          </div>
        </form>
      </CardContent>
    </Card>
  );
}
