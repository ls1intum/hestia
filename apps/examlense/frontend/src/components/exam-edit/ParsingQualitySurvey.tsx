import { useState } from "react";
import { CheckCircle2, Send, SkipForward } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Slider } from "@/components/ui/slider";
import { submitParseSurvey } from "@/lib/api-client";
import { useToast } from "@/hooks/use-toast";

const ASPECTS = ["speed", "contentCorrectness", "structure"] as const;
type Aspect = (typeof ASPECTS)[number];

const ASPECT_LABELS: Record<Aspect, string> = {
  speed: "Speed",
  contentCorrectness: "Content correctness",
  structure: "Exam content structure",
};

/**
 * Optional survey shown on the evaluating/solving wait screen, rating how well
 * parsing worked on three 1–10 aspects. Skippable; results land in the
 * `parse_survey` table. Collapses to an inline thank-you after submit/skip.
 */
export const ParsingQualitySurvey = ({ examId }: { examId: string }) => {
  const { toast } = useToast();
  const [values, setValues] = useState<Record<Aspect, number>>({
    speed: 5,
    contentCorrectness: 5,
    structure: 5,
  });
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);

  const submit = async () => {
    setSubmitting(true);
    try {
      await submitParseSurvey({
        exam_id: examId,
        speed: values.speed,
        content_correctness: values.contentCorrectness,
        structure: values.structure,
      });
      setDone(true);
    } catch {
      toast({ title: "Could not submit feedback. Please try again.", variant: "destructive" });
    } finally {
      setSubmitting(false);
    }
  };

  if (done) {
    return (
      <Card className="mt-hestia-5 w-full">
        <CardContent className="flex flex-col items-center gap-hestia-2 py-hestia-5 text-center">
          <CheckCircle2 size={28} className="text-hestia-success" />
          <p className="text-sm text-hestia-text-muted">
            Thanks for your feedback!
          </p>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className="mt-hestia-5 w-full text-left">
      <CardHeader>
        <CardTitle className="text-base">How well did parsing work?</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-hestia-4">
        {ASPECTS.map((aspect) => (
          <div key={aspect} className="flex flex-col gap-hestia-2">
            <div className="flex items-center justify-between">
              <Label>{ASPECT_LABELS[aspect]}</Label>
              <span className="text-sm tabular-nums text-hestia-text-muted">
                {values[aspect]}
              </span>
            </div>
            <Slider
              min={1}
              max={10}
              step={1}
              value={[values[aspect]]}
              disabled={submitting}
              onValueChange={(v) =>
                setValues((prev) => ({ ...prev, [aspect]: v[0] }))
              }
            />
          </div>
        ))}
        <p className="text-xs text-hestia-text-muted">
          Your feedback helps improve the tool for the future.
        </p>
        <div className="flex justify-end gap-hestia-2">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setDone(true)}
            disabled={submitting}
            className="gap-1 text-hestia-text-muted"
          >
            <SkipForward size={14} />
            Skip
          </Button>
          <Button size="sm" onClick={submit} disabled={submitting} className="gap-1">
            <Send size={14} />
            Submit
          </Button>
        </div>
      </CardContent>
    </Card>
  );
};
