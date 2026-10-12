import { Card, CardHeader, CardTitle, CardContent, CardDescription } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { ArrowRight, Pencil } from "lucide-react";
import { WorkshopInput, TemplateInfo } from "@/lib/workshop-generator";
import { TemplateUploadBox } from "./TemplateUploadBox";

interface Props {
  settings: Partial<WorkshopInput>;
  /** The course's slide template, shown read-only; null means the default design */
  courseTemplate?: TemplateInfo | null;
  onEdit: () => void;
  onContinue: () => void;
  isLoading?: boolean;
}

export default function CourseSummary({ settings, onEdit, onContinue, isLoading = false, courseTemplate }: Props) {
  return (
    <div className="space-y-4">
      <Card className="border-border/60 shadow-lg">
        <CardHeader>
          <CardTitle className="font-display text-2xl">Course Settings Summary</CardTitle>
          <CardDescription>
            This session will be generated using the following settings inherited from the Course.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-6">
          <div className="grid grid-cols-2 gap-4">
            <div>
              <p className="text-sm font-semibold text-muted-foreground font-body">Type</p>
              <p className="font-medium text-foreground">{settings.sessionType === "other" ? settings.sessionTypeOther : settings.sessionType}</p>
            </div>
            <div>
              <p className="text-sm font-semibold text-muted-foreground font-body">Duration</p>
              <p className="font-medium text-foreground">{settings.duration} min</p>
            </div>
            <div className="col-span-2">
              <p className="text-sm font-semibold text-muted-foreground font-body">Activities</p>
              <div className="flex flex-wrap gap-1 mt-1">
                {settings.selectedActivities?.length ? settings.selectedActivities.map(a => (
                  <Badge key={a} variant="secondary">{a}</Badge>
                )) : <span className="text-sm text-muted-foreground">None specified</span>}
              </div>
            </div>
            <div className="col-span-2">
              <p className="text-sm font-semibold text-muted-foreground font-body">Materials</p>
              <div className="flex flex-wrap gap-1 mt-1">
                {settings.availableMaterials?.length ? settings.availableMaterials.map(m => (
                  <Badge key={m} variant="outline">{m}</Badge>
                )) : <span className="text-sm text-muted-foreground">None specified</span>}
              </div>
            </div>
          
            <div className="col-span-2 pt-4 border-t border-border/50">
              <p className="text-sm font-semibold text-muted-foreground font-body mb-2">Slide Template</p>
              <div className="w-[220px]">
                <TemplateUploadBox
                  fileName={courseTemplate ? courseTemplate.name || "Course template" : null}
                  caption="From course template"
                  emptyHint="Default design"
                  note="Can be edited in a later step."
                />
              </div>
            </div>
          </div>
          <div className="flex justify-between pt-4 border-t border-border/50">
            <Button type="button" variant="outline" onClick={onEdit} disabled={isLoading} className="gap-2">
              <Pencil className="h-4 w-4" /> Edit Settings
            </Button>
            <Button onClick={onContinue} size="lg" disabled={isLoading} className="gap-2">
              Continue to Goals <ArrowRight className="h-4 w-4" />
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
