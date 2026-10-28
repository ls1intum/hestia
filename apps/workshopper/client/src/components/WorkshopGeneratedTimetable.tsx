import { useRef } from "react";
import { LearningGoalPlan, WorkshopInput, WorkshopSession } from "@/lib/workshop-generator";
import { Card, CardContent, CardHeader, CardDescription } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { ArrowLeft, ArrowRight, ChevronDown, Clock, Plus, Loader2, X } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
  DropdownMenuSeparator,
} from "@/components/ui/dropdown-menu";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { DndContext, closestCenter, KeyboardSensor, PointerSensor, useSensor, useSensors } from "@dnd-kit/core";
import { SortableContext, sortableKeyboardCoordinates, verticalListSortingStrategy } from "@dnd-kit/sortable";
import type { LeaveGuard } from "@/hooks/useWizardState";
import { useTimetableEditing, EvaluateMappings } from "@/hooks/useTimetableEditing";
import { useBlockRegeneration } from "@/hooks/useBlockRegeneration";
import { SortableBlockRow } from "./timetable/SortableBlockRow";
import { ADDABLE_PHASES, PHASES } from "@/lib/phases";

interface Props {
  session: WorkshopSession;
  goals: LearningGoalPlan[];
  meta: WorkshopInput;
  onBack: () => void;
  onNext: (latestSession: WorkshopSession) => void;
  onSaveSession?: (session: WorkshopSession) => void;
  onMetaChanged?: (meta: WorkshopInput) => void;
  /** Lets the wizard ask this step before navigating away while a block has unsaved edits. */
  registerLeaveGuard?: (guard: LeaveGuard | null) => void;
}

/**
 * Timetable step: the generated blocks as sortable rows. Block state, edit mode and saving live in
 * useTimetableEditing; changes that need the LLM in useBlockRegeneration.
 */
export default function WorkshopGeneratedTimetable({ session: initialSession, goals, meta, onBack, onNext, onSaveSession, onMetaChanged, registerLeaveGuard }: Props) {
  const onNextRef = useRef(onNext);
  onNextRef.current = onNext;
  const onSaveSessionRef = useRef(onSaveSession);
  onSaveSessionRef.current = onSaveSession;

  // Saving an evaluate block's draft regenerates it (useBlockRegeneration), which itself needs the
  // editing state; the ref connects the two hooks.
  const confirmMappingsRef = useRef<(dndId: string, mappings: EvaluateMappings) => Promise<boolean>>(async () => false);
  const busyRef = useRef(false);
  const timetable = useTimetableEditing({
    initialSession, goals, meta, onSaveSession, registerLeaveGuard,
    confirmMappings: (dndId, mappings) => confirmMappingsRef.current(dndId, mappings),
    isBusy: () => busyRef.current,
  });
  const { regeneratingBlockId, hasConfirmedEvaluatePriorities, switchActivity, addActivity, confirmEvaluateMappings } =
    useBlockRegeneration({ editing: timetable, initialSession, goals, meta, onMetaChanged });
  confirmMappingsRef.current = confirmEvaluateMappings;
  busyRef.current = regeneratingBlockId !== null;

  const {
    blocks, sessionTitle, latestSession, expandedBlocks, toggleExpand, editing, setEditing,
    editingBlockId, toggleEditBlock, cancelEditBlock, pendingMappingsRef,
    confirmLeave, leaveDialog, setLeaveDialog, isSavingDraft, discardAndProceed, saveAndProceed,
    saveTitle, saveObjective, saveStep, saveStepTime, saveBlockDuration, saveSectionDuration, addStep, deleteStep, deleteActivity,
    addBlock, deleteBlock, lastDeletedBlock, setLastDeletedBlock, undoDelete, handleDragEnd,
  } = timetable;

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  );

  const handleBack = () => confirmLeave(() => onBack());

  const handleNext = () => confirmLeave(() => {
    const latest = latestSession();
    onSaveSessionRef.current?.(latest);
    onNextRef.current?.(latest);
  });

  const evaluateBlockNeedsPrioritySelection = goals.length > 2 && !hasConfirmedEvaluatePriorities;
  const totalDuration = blocks.reduce((s, b) => s + b.duration, 0);
  const targetDuration = meta.duration || totalDuration;
  const timePercentage = Math.min(100, Math.round((totalDuration / targetDuration) * 100));

  return (
    <div className="space-y-4">
      <Card className="border-border/60 shadow-lg">
        <CardHeader>
          <div className="flex items-start justify-between gap-4">
            <div className="flex-1 min-w-0">
              <h2 className="font-display font-bold text-3xl text-foreground mt-0.5">
                <span className="py-1">{sessionTitle || "Workshop Title"}</span>
              </h2>
              <CardDescription className="mt-1">
                Edit block contents, activities, time allocation and ordering.
              </CardDescription>
            </div>
          </div>
        </CardHeader>
        <CardContent>


          <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
            <SortableContext items={blocks.map(b => b.dndId)} strategy={verticalListSortingStrategy}>
              {blocks.map(block => {
                const isNeedsPriority = (goals.length > 2 && !hasConfirmedEvaluatePriorities) && block.phase === "EVALUATE";
                return (
                  <SortableBlockRow
                    key={block.dndId}
                    block={block}
                    goals={goals}
                    needsEvaluatePriority={isNeedsPriority}
                    onConfirmEvaluateMappings={(mappings) => { confirmEvaluateMappings(block.dndId, mappings); }}
                    onPendingMappingsChange={(mappings) => { pendingMappingsRef.current = { dndId: block.dndId, mappings }; }}
                    isExpanded={expandedBlocks[block.dndId] ?? (block.phase === "EVALUATE" && isNeedsPriority)}
                    editing={editing}
                    meta={meta}
                    isRegenerating={regeneratingBlockId === block.dndId}
                    selectedActivities={meta.selectedActivities || []}
                    isEditMode={editingBlockId === block.dndId}
                    onToggleEditMode={() => toggleEditBlock(block.dndId)}
                    onCancelEditMode={() => cancelEditBlock(block.dndId)}
                    onToggleExpand={() => toggleExpand(block.dndId)}
                    onEditTitle={() => setEditing({ type: "title", blockId: block.dndId })}
                    onSaveTitle={v => saveTitle(block.dndId, v)}
                    onSaveStep={(sIdx, stIdx, v) => saveStep(block.dndId, sIdx, stIdx, v)}
                    onSaveStepTime={(sIdx, stIdx, v) => saveStepTime(block.dndId, sIdx, stIdx, v)}
                    onSaveBlockDuration={v => saveBlockDuration(block.dndId, v)}
                    onSaveSectionDuration={(sIdx, v) => saveSectionDuration(block.dndId, sIdx, v)}
                    onSaveObjective={v => saveObjective(block.dndId, v)}
                    onDeleteBlock={() => deleteBlock(block.dndId)}
                    onSwitchActivity={(oldAct, newAct) => switchActivity(block.dndId, oldAct, newAct)}
                    onDeleteActivity={method => deleteActivity(block.dndId, method)}
                    onAddActivity={method => addActivity(block.dndId, method)}
                    onAddStep={text => addStep(block.dndId, text)}
                    onDeleteStep={(sIdx, stIdx) => deleteStep(block.dndId, sIdx, stIdx)}
                  />
                );
              })}
            </SortableContext>

            <div className="mt-4 flex justify-center">
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button
                    variant="outline"
                    className="w-full border-dashed border-2 py-6 text-muted-foreground hover:text-foreground hover:border-primary/50"
                  >
                    <Plus className="mr-2 h-4 w-4" /> Add Block <ChevronDown className="ml-2 h-4 w-4 opacity-60" />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent className="w-56">
                  {ADDABLE_PHASES.map(t => (
                    <DropdownMenuItem key={t.phase} onClick={() => addBlock(t.phase)}>
                      <span className="mr-2">{t.emoji}</span>{t.label}
                    </DropdownMenuItem>
                  ))}
                  <DropdownMenuSeparator />
                  <DropdownMenuItem onClick={() => addBlock("CUSTOM")}>
                    <span className="mr-2">{PHASES.CUSTOM.emoji}</span>{PHASES.CUSTOM.label}
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </div>
          </DndContext>
        </CardContent>

        {/* Sticky footer */}
        <div className="sticky bottom-4 z-20 mx-4 mb-4 rounded-xl border border-border/80 bg-surface backdrop-blur-md shadow-xl p-3 flex items-center justify-between gap-4 transition-all">
          <Button
            variant="outline"
            size="sm"
            onClick={handleBack}
            className="gap-2 font-body shrink-0 bg-transparent border-[1.5px] border-primary text-primary hover:bg-primary/5 transition-all duration-150"
          >
            <ArrowLeft className="h-4 w-4" /> Previous
          </Button>

          <div className="flex-1 flex items-center justify-center gap-4 text-sm font-medium">
            <div className="flex items-center gap-1.5 text-muted-foreground">
              <Clock className={`h-4 w-4 ${totalDuration > targetDuration ? "text-destructive" : "text-primary"}`} />
              <span className="hidden sm:inline">Time Allocated</span>
            </div>
            {totalDuration > targetDuration ? (
              <span className="flex items-center gap-1.5 bg-destructive/15 text-foreground border border-destructive/50 px-2.5 py-0.5 rounded-md font-bold shadow-sm">
                <span className="text-destructive">⚠</span> {totalDuration} / {targetDuration} min
              </span>
            ) : (
              <span className="text-foreground font-bold">
                {totalDuration} / {targetDuration} min
              </span>
            )}
            <div className="hidden sm:block h-1 w-[128px] bg-muted/60 rounded-full overflow-hidden shadow-inner">
              <div
                className={`h-full transition-all duration-500 ease-out ${totalDuration > targetDuration ? "bg-destructive" : "bg-primary"}`}
                style={{ width: `${timePercentage}%` }}
              />
            </div>
          </div>

          {totalDuration > targetDuration ? (
            <Button size="sm" disabled className="gap-2 shadow-md bg-muted text-muted-foreground font-semibold shrink-0 rounded-lg">
              {regeneratingBlockId !== null ? "Generating..." : "Next Step"} <ArrowRight className="h-4 w-4" />
            </Button>
          ) : (
            <div
              title={evaluateBlockNeedsPrioritySelection ? "please review Understanding Check first" : undefined}
              className={evaluateBlockNeedsPrioritySelection ? "shrink-0 cursor-help" : "shrink-0"}
            >
              <Button
                size="sm"
                onClick={handleNext}
                disabled={evaluateBlockNeedsPrioritySelection || regeneratingBlockId !== null}
                className={`gap-2 shadow-md hover:shadow-lg bg-primary text-primary-foreground font-semibold shrink-0 rounded-lg transition-all duration-150 ${evaluateBlockNeedsPrioritySelection ? "pointer-events-none" : ""}`}
              >
                {regeneratingBlockId !== null ? "Generating..." : "Next Step"} <ArrowRight className="h-4 w-4" />
              </Button>
            </div>
          )}
        </div>
      </Card>

      {/* Undo delete banner */}
      {lastDeletedBlock && (
        <div className="fixed bottom-24 right-4 z-50 flex items-center gap-3 bg-foreground text-background text-sm font-body px-4 py-3 rounded-xl shadow-xl border border-border/10">
          <span className="opacity-80">Block deleted</span>
          <button
            onClick={undoDelete}
            className="font-semibold underline underline-offset-2 hover:opacity-80 transition-opacity whitespace-nowrap"
          >
            Undo
          </button>
          <button
            onClick={() => setLastDeletedBlock(null)}
            className="opacity-50 hover:opacity-100 transition-opacity ml-1"
          >
            <X className="h-3.5 w-3.5" />
          </button>
        </div>
      )}

      {/* Unsaved edit-mode draft: decide before leaving it */}
      <AlertDialog open={leaveDialog !== null} onOpenChange={(open) => { if (!open && !isSavingDraft) setLeaveDialog(null); }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {(() => {
                const block = blocks.find(b => b.dndId === editingBlockId);
                return `"${block?.phaseLabel || block?.phase || "This block"}" has unsaved changes`;
              })()}
            </AlertDialogTitle>
            <AlertDialogDescription>
              The block is still in edit mode. Save your changes before you continue, or discard them.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={isSavingDraft} onClick={() => setLeaveDialog(null)}>Stay</AlertDialogCancel>
            <Button
              variant="outline"
              disabled={isSavingDraft}
              className="text-destructive hover:text-destructive"
              onClick={discardAndProceed}
            >
              Discard changes
            </Button>
            <Button
              disabled={isSavingDraft}
              onClick={saveAndProceed}
            >
              {isSavingDraft ? <><Loader2 className="h-4 w-4 mr-2 animate-spin" /> Saving…</> : "Save and continue"}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
