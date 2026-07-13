import { useState, useEffect } from "react";
import { ArrowLeft, ArrowRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { WorkshopInput, SessionType } from "@/lib/workshop-generator";

interface Props {
  onNext: (input: Partial<WorkshopInput>) => void;
  isLoading?: boolean;
  initialInput?: Partial<WorkshopInput>;
  entityType?: "SESSION" | "LECTURE";
  /** N-4: when provided, renders a Back button (used when editing lecture session settings) */
  onBack?: () => void;
}


export default function WorkshopFormStep1({ onNext, isLoading = false, initialInput, entityType = "SESSION", onBack }: Props) {
  const [title, setTitle] = useState(initialInput?.title ?? "");
  const [duration, setDuration] = useState<number | "">(initialInput?.duration ?? 90);
  const [participants, setParticipants] = useState<number | "">(initialInput?.participants ?? 15);
  const [sessionType, setSessionType] = useState<SessionType>((initialInput?.sessionType as SessionType) ?? "lecture");
  const [sessionTypeOther, setSessionTypeOther] = useState(initialInput?.sessionTypeOther ?? "");
  const [studentBackground, setStudentBackground] = useState(initialInput?.studentBackground ?? "");

  useEffect(() => {
    if (initialInput?.title !== undefined) {
      setTitle(initialInput.title);
    }
  }, [initialInput?.title]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const finalDuration = Math.max(0, Math.min(480, Number(duration) || 0));
    const finalParticipants = Math.max(0, Math.min(500, Number(participants) || 0));
    setDuration(finalDuration);
    setParticipants(finalParticipants);
    onNext({
      title: title.trim() || undefined,
      duration: finalDuration,
      participants: finalParticipants,
      sessionType,
      sessionTypeOther: sessionType === "other" ? sessionTypeOther.trim() || undefined : undefined,
      studentBackground: studentBackground.trim() || undefined,
    });
  };

  // I-1: require at least 10 minutes so the AI can produce a meaningful timetable
  const canSubmit = !isLoading && Number(duration) >= 10 && (entityType !== "LECTURE" || title.trim() !== "");

  return (
    <Card className="border-border/60 shadow-lg">
      <CardHeader>
        <CardTitle className="font-display text-3xl">Session Setup</CardTitle>
        <CardDescription>
          Configure the basic parameters for your session.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={handleSubmit} className="space-y-6">
          {/* Title — required for lectures, optional for sessions */}
          <div className="space-y-2">
            <Label htmlFor="title" className="font-body font-medium">
              {entityType === "LECTURE" ? "Lecture Title" : "Session Title"}
              {entityType === "SESSION" && <span className="text-muted-foreground font-normal"> (optional)</span>}
            </Label>
            <Input
              id="title"
              placeholder={entityType === "LECTURE" ? "e.g. Introduction to Machine Learning" : "e.g. Week 3 — Regression Analysis"}
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              disabled={isLoading}
              required={entityType === "LECTURE"}
              maxLength={200}
            />
          </div>

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

          {/* Duration */}
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

          {/* Participants */}
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


          {/* I-1: show hint when duration is too short */}
          {Number(duration) > 0 && Number(duration) < 10 && (
            <p className="text-xs text-destructive font-body">Minimum duration is 10 minutes.</p>
          )}

          <div className="flex justify-between pt-2">
            {/* N-4: back button when in lecture-session editing mode */}
            {onBack ? (
              <Button type="button" variant="outline" onClick={onBack} disabled={isLoading} className="gap-2 font-body">
                <ArrowLeft className="h-4 w-4" /> Back
              </Button>
            ) : <div />}
            <Button type="submit" size="lg" className="px-10 font-body font-semibold text-base gap-2" disabled={!canSubmit}>
              Next step <ArrowRight className="h-4 w-4" />
            </Button>
          </div>
        </form>
      </CardContent>
    </Card>
  );
}
