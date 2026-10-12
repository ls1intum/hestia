import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { ArrowLeft, Check, Loader2 } from "lucide-react";
import { TemplateInfo } from "@/lib/workshop-generator";
import { uploadCourseTemplate, deleteCourseTemplate } from "@/lib/api";
import { toast } from "@/hooks/use-toast";
import { TemplateUploadBox } from "./TemplateUploadBox";

interface Props {
  /** The saved course; null until step 1 has been saved */
  courseId: string | null;
  template: TemplateInfo | null;
  onTemplateChange: (info: TemplateInfo | null) => void;
  onBack: () => void;
  onFinish: () => void;
  isLoading?: boolean;
}

/** Last step of creating or editing a course: its slide template, used by all of its sessions. */
export default function CourseTemplateStep({ courseId, template, onTemplateChange, onBack, onFinish, isLoading = false }: Props) {
  const [busy, setBusy] = useState(false);

  const upload = async (file: File) => {
    if (!courseId) return;
    setBusy(true);
    try {
      await uploadCourseTemplate(courseId, file);
      onTemplateChange({ source: "COURSE", name: file.name });
      toast({ title: "Course template saved", description: "It will be used for every session in this course." });
    } catch (err) {
      toast({ title: "Upload failed", description: err instanceof Error ? err.message : "Error", variant: "destructive" });
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (!courseId) return;
    setBusy(true);
    try {
      await deleteCourseTemplate(courseId);
      onTemplateChange(null);
      toast({ title: "Course template removed", description: "Sessions in this course now use the default design." });
    } catch {
      toast({ title: "Failed to remove template", variant: "destructive" });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-2">
      <Card className="border-border/60 shadow-lg">
        <CardHeader>
          <CardTitle className="font-display text-3xl">Slide Template</CardTitle>
          <CardDescription>
            Optional. Upload your university's PowerPoint template (.pptx): its title slide, logo and footer are used for
            every session in this course. A session can still remove it or upload its own.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-6">
          <div className="w-[220px]">
            <TemplateUploadBox
              fileName={template ? template.name || "Course template" : null}
              caption="Click to replace"
              isBusy={busy}
              onUpload={courseId ? upload : undefined}
              onRemove={remove}
              removeTitle="Remove course template"
              note={courseId ? undefined : "Available once the course has been saved."}
            />
          </div>

          <div className="flex justify-between pt-2">
            <Button type="button" variant="outline" onClick={onBack} disabled={isLoading || busy} className="gap-2 font-body">
              <ArrowLeft className="h-4 w-4" /> Previous step
            </Button>
            <Button
              type="button"
              size="lg"
              onClick={onFinish}
              disabled={isLoading || busy}
              className="px-10 font-body font-semibold text-base gap-2"
            >
              {isLoading ? <><Loader2 className="h-5 w-5 animate-spin" /> Saving…</> : <>Finish course <Check className="h-4 w-4" /></>}
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
