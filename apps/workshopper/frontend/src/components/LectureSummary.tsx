import React from "react";
import { Card, CardHeader, CardTitle, CardContent, CardDescription } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { ArrowRight, Pencil } from "lucide-react";
import { WorkshopInput } from "@/lib/workshop-generator";

interface Props {
  settings: Partial<WorkshopInput>;
  onEdit: () => void;
  onContinue: () => void;
  isLoading?: boolean;
}

export default function LectureSummary({ settings, onEdit, onContinue, isLoading = false }: Props) {
  return (
    <div className="space-y-4">
      <Card className="border-border/60 shadow-lg">
        <CardHeader>
          <CardTitle className="font-display text-2xl">Lecture Settings Summary</CardTitle>
          <CardDescription>
            This session will be generated using the following settings inherited from the Lecture.
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
