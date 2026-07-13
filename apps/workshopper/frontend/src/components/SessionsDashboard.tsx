import { useEffect, useState } from "react";
import { Plus, BookOpen, Clock, CheckCircle2, FileEdit, MoreVertical, Pencil, FileText, Presentation, Trash2, GraduationCap, Loader2, ChevronRight, ChevronDown, Sparkles } from "lucide-react";
import { Button } from "@/components/ui/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from "@/components/ui/dialog";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Input } from "@/components/ui/input";
import { listSessions, deleteSession, renameSession, downloadPdf, downloadPptx, moveSession, downloadLectureZip, reorderSessions } from "@/lib/api";
import type { SessionSummary } from "@/lib/workshop-generator";

interface Props {
  onNewSession: () => void;
  onNewLecture: () => void;
  onNewSessionFromLecture: (id: string) => void;
  onResumeSession: (id: string) => void;
}

function parseDate(dateStr: string | unknown): Date {
  if (Array.isArray(dateStr)) {
    // Java LocalDateTime serialized as [year, month, day, hour, minute, second?, nano?]
    const [y, mo, d, h = 0, mi = 0, s = 0] = dateStr as number[];
    return new Date(y, mo - 1, d, h, mi, s);
  }
  return new Date(dateStr as string);
}

function formatRelativeTime(dateInput: string | unknown): string {
  const date = parseDate(dateInput);
  if (isNaN(date.getTime())) return "";
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffMins = Math.floor(diffMs / 60000);
  const diffHours = Math.floor(diffMs / 3600000);
  const diffDays = Math.floor(diffMs / 86400000);

  if (diffMins < 1) return "just now";
  if (diffMins < 60) return `${diffMins}m ago`;
  if (diffHours < 24) return `${diffHours}h ago`;
  if (diffDays < 7) return `${diffDays}d ago`;
  return date.toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

const STEP_LABELS: Record<string, string> = {
  "input-1": "Step 1 – Setup",
  "input-2": "Step 2 – Activities",
  "goals": "Step 3 – Learning Goals",
  "ordering": "Step 4 – Goal Order",
  "skeleton": "Step 5 – Timetable",
  "result": "Complete",
};

export default function SessionsDashboard({ onNewSession, onNewLecture, onNewSessionFromLecture, onResumeSession }: Props) {
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [collapsedLectures, setCollapsedLectures] = useState<Set<string>>(new Set());
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renamingTitle, setRenamingTitle] = useState<string>("");
  const [deletingItem, setDeletingItem] = useState<{ id: string, type: "SESSION" | "LECTURE" } | null>(null);

  const toggleLecture = (id: string) => {
    setCollapsedLectures(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  useEffect(() => {
    loadSessions();
  }, []);

  const loadSessions = () => {
    setLoading(true);
    listSessions()
      .then(setSessions)
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  };

  const handleDeleteClick = (e: React.MouseEvent, id: string, type: "SESSION" | "LECTURE") => {
    e.stopPropagation();
    setDeletingItem({ id, type });
  };

  const confirmDelete = async () => {
    if (!deletingItem) return;
    try {
      await deleteSession(deletingItem.id);
      loadSessions();
    } catch (err: any) {
      alert("Failed to delete: " + err.message);
    }
    setDeletingItem(null);
  };

  const handleRenameClick = (e: React.MouseEvent, id: string, currentTitle: string) => {
    e.stopPropagation();
    setRenamingId(id);
    setRenamingTitle(currentTitle);
  };

  const submitRename = async () => {
    if (renamingId && renamingTitle.trim() !== "") {
      const session = sessions.find(s => s.id === renamingId);
      if (session && session.title !== renamingTitle) {
        try {
          await renameSession(renamingId, renamingTitle.trim());
          loadSessions();
        } catch (err: any) {
          alert("Failed to rename session: " + err.message);
        }
      }
    }
    setRenamingId(null);
  };

  const handleExportPdf = async (e: React.MouseEvent, id: string) => {
    e.stopPropagation();
    try {
      await downloadPdf(id);
    } catch (err: any) {
      alert("Failed to export PDF: " + err.message);
    }
  };

  const handleExportPptx = async (e: React.MouseEvent, id: string) => {
    e.stopPropagation();
    try {
      await downloadPptx(id);
    } catch (err: any) {
      alert("Failed to export PPTX: " + err.message);
    }
  };

  const handleExportLectureZip = async (e: React.MouseEvent, id: string) => {
    e.stopPropagation();
    try {
      await downloadLectureZip(id);
    } catch (err: any) {
      alert("Failed to export Lecture ZIP: " + err.message);
    }
  };

  return (
    <div className="min-h-screen bg-background">
      {/* Hero header */}
      <div className="relative overflow-hidden border-b border-border/50 bg-gradient-to-br from-primary/10 via-background to-accent/10">
        <div className="absolute inset-0 bg-grid-pattern opacity-[0.03]" />
        <div className="relative max-w-5xl mx-auto px-4 sm:px-6 py-10 sm:py-12">
          <div className="flex items-center gap-3 mb-3">
            <div className="w-10 h-10 rounded-2xl bg-primary/20 flex items-center justify-center">
              <GraduationCap className="h-5 w-5 text-primary" />
            </div>
            <h1 className="font-display font-bold text-3xl sm:text-4xl text-foreground tracking-tight">
              Workshopper
            </h1>
          </div>
          <p className="text-muted-foreground font-body text-base max-w-xl">
            Design constructively aligned workshop sessions with AI assistance.
          </p>
        </div>
      </div>

      {/* Sessions list */}
      <main className="max-w-5xl mx-auto px-4 sm:px-6 py-10">
        {loading ? (
          <div className="flex items-center justify-center py-20 text-muted-foreground gap-3">
            <Loader2 className="h-5 w-5 animate-spin" />
            <span className="font-body">Loading sessions…</span>
          </div>
        ) : error ? (
          <div className="text-center py-20">
            <p className="text-destructive font-body mb-2">Failed to load sessions</p>
            <p className="text-sm text-muted-foreground">{error}</p>
          </div>
        ) : sessions.length === 0 ? (
          <div className="text-center py-24">
            <div className="w-16 h-16 rounded-2xl bg-muted flex items-center justify-center mx-auto mb-4">
              <BookOpen className="h-8 w-8 text-muted-foreground" />
            </div>
            <h2 className="font-display font-semibold text-xl text-foreground mb-2">No items yet</h2>
            <p className="text-muted-foreground font-body mb-6">
              Create your first lecture or standalone session to get started.
            </p>
            <div className="flex justify-center gap-3">
              <Button variant="outline" onClick={onNewLecture} className="gap-2 rounded-xl">
                <Plus className="h-4 w-4" />
                Create Lecture
              </Button>
              <Button id="empty-new-session-btn" onClick={onNewSession} className="gap-2 rounded-xl shadow-sm">
                <Plus className="h-4 w-4" />
                Create Session
              </Button>
            </div>
          </div>
        ) : (
          <div>
            <div className="flex items-center justify-between mb-6">
              <h2 className="font-display font-semibold text-xl text-foreground">
                Your Library
                <span className="ml-2 text-sm font-normal text-muted-foreground font-body">
                  ({sessions.length} items)
                </span>
              </h2>
              <div className="flex gap-2">
                <Button variant="outline" onClick={onNewLecture} className="gap-2 rounded-xl">
                  <Plus className="h-4 w-4" />
                  New Lecture
                </Button>
                <Button id="new-session-btn" onClick={onNewSession} className="gap-2 rounded-xl shadow-sm">
                  <Plus className="h-4 w-4" />
                  New Session
                </Button>
              </div>
            </div>
            
            {/* Split items */}
            {(() => {
              const lectures = sessions.filter(s => s.type === "LECTURE");
              const standaloneSessions = sessions.filter(s => s.type !== "LECTURE" && !s.lectureId);
              
              const renderSessionCard = (session: SessionSummary, isChild = false) => (
                <div
                  key={session.id}
                  id={`session-card-${session.id}`}
                  className={`group text-left rounded-2xl border border-border/60 bg-card hover:bg-card/80 hover:border-primary/30 hover:shadow-md hover:shadow-primary/5 transition-all duration-200 p-5 flex items-center gap-4 cursor-pointer ${isChild ? "ml-8" : ""}`}
                  onClick={() => onResumeSession(session.id)}
                  draggable={true}
                  onDragStart={(e) => {
                    if (session.id) {
                      e.dataTransfer.setData("text/plain", session.id);
                      e.currentTarget.classList.add("opacity-50");
                    }
                  }}
                  onDragEnd={(e) => {
                    e.currentTarget.classList.remove("opacity-50");
                  }}
                  onDragOver={(e) => {
                    e.preventDefault();
                    e.currentTarget.classList.add("border-primary", "border-t-4");
                  }}
                  onDragLeave={(e) => {
                    e.currentTarget.classList.remove("border-primary", "border-t-4");
                  }}
                  onDrop={async (e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    e.currentTarget.classList.remove("border-primary", "border-t-4");
                    const draggedId = e.dataTransfer.getData("text/plain");
                    if (draggedId && draggedId !== session.id) {
                      const draggedSession = sessions.find(s => s.id === draggedId);
                      if (!draggedSession) return;
                      
                      try {
                        // If moving between different parents, move it first
                        if (draggedSession.lectureId !== session.lectureId) {
                          await moveSession(draggedId, session.lectureId || "");
                        }
                        
                        // Calculate new order
                        const siblings = session.lectureId 
                          ? sessions.filter(s => s.lectureId === session.lectureId)
                          : sessions.filter(s => s.type !== "LECTURE" && !s.lectureId);
                          
                        const newOrderIds = siblings.map(s => s.id).filter(id => id !== draggedId);
                        const targetIndex = newOrderIds.indexOf(session.id);
                        newOrderIds.splice(targetIndex, 0, draggedId);
                        
                        await reorderSessions(newOrderIds);
                        loadSessions();
                      } catch (err: any) {
                        alert("Failed to reorder: " + err.message);
                      }
                    }
                  }}
                >
                  {(() => {
                    const isFinished = session.status === "complete" && session.currentStep === "finished";
                    const isReadyForPrep = session.status === "complete" && session.currentStep !== "finished";
                    const isDraft = session.status !== "complete";
                    return (
                      <div className={`flex-shrink-0 w-10 h-10 rounded-xl flex items-center justify-center transition-colors ${
                        isFinished ? "bg-emerald-500/15 text-emerald-500"
                        : isReadyForPrep ? "bg-blue-500/15 text-blue-500"
                        : "bg-amber-500/15 text-amber-500"
                      }`}>
                        {isFinished ? <CheckCircle2 className="h-5 w-5" /> : isReadyForPrep ? <Sparkles className="h-5 w-5" /> : <FileEdit className="h-5 w-5" />}
                      </div>
                    );
                  })()}
                  <div className="flex-1 min-w-0">
                    {(() => {
                      const isFinished = session.status === "complete" && session.currentStep === "finished";
                      const isReadyForPrep = session.status === "complete" && session.currentStep !== "finished";
                      const statusLabel = isFinished ? "Completed" : isReadyForPrep ? "Ready for preparation" : "In progress";
                      const statusClass = isFinished
                        ? "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400"
                        : isReadyForPrep
                        ? "bg-blue-500/15 text-blue-600 dark:text-blue-400"
                        : "bg-amber-500/15 text-amber-600 dark:text-amber-400";
                      return (
                        <div className="flex items-center gap-2 mb-0.5">
                          <span className="font-display font-semibold text-foreground truncate">{session.title || "Workshop Session"}</span>
                          <span className={`flex-shrink-0 text-xs font-medium px-2 py-0.5 rounded-full font-body ${statusClass}`}>
                            {statusLabel}
                          </span>
                        </div>
                      );
                    })()}
                    {session.learningGoal && <p className="text-sm text-muted-foreground font-body truncate">{session.learningGoal}</p>}
                    <div className="flex items-center gap-3 mt-1.5">
                      <span className="flex items-center gap-1 text-xs text-muted-foreground font-body">
                        <Clock className="h-3 w-3" />
                        {formatRelativeTime(session.updatedAt || session.createdAt)}
                      </span>
                      {session.status === "draft" && session.currentStep && (
                        <span className="text-xs text-muted-foreground font-body">{STEP_LABELS[session.currentStep] ?? session.currentStep}</span>
                      )}
                    </div>
                  </div>
                  <div className="flex items-center gap-2 flex-shrink-0" onClick={e => e.stopPropagation()}>
                    <DropdownMenu>
                      <DropdownMenuTrigger asChild>
                        <Button variant="ghost" size="icon" className="text-muted-foreground/50 hover:text-foreground">
                          <MoreVertical className="h-4 w-4" />
                        </Button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align="end" className="w-48 font-body">
                        <DropdownMenuItem onClick={(e) => handleRenameClick(e as any, session.id, session.title || "Workshop Session")}>
                          <Pencil className="mr-2 h-4 w-4" /><span>Rename</span>
                        </DropdownMenuItem>
                        {session.status === "complete" && (
                          <>
                            <DropdownMenuItem onClick={(e) => handleExportPptx(e as any, session.id)}><Presentation className="mr-2 h-4 w-4" /><span>Export Slides (PPTX)</span></DropdownMenuItem>
                            <DropdownMenuItem onClick={(e) => handleExportPdf(e as any, session.id)}><FileText className="mr-2 h-4 w-4" /><span>Export Timetable (PDF)</span></DropdownMenuItem>
                          </>
                        )}
                        <DropdownMenuItem onClick={(e) => handleDeleteClick(e as any, session.id, "SESSION")} className="text-destructive focus:text-destructive focus:bg-destructive/10">
                          <Trash2 className="mr-2 h-4 w-4" /><span>Delete</span>
                        </DropdownMenuItem>
                      </DropdownMenuContent>
                    </DropdownMenu>
                  </div>
                </div>
              );

              return (
                <div className="grid gap-6">
                  {lectures.length > 0 && (
                    <div className="space-y-3">
                      <h3 className="font-display font-semibold text-lg text-foreground flex items-center gap-2">
                        <BookOpen className="h-5 w-5 text-primary" /> Lectures
                      </h3>
                      <div className="grid gap-3">
                        {lectures.map((lecture) => {
                          const childSessions = sessions.filter(s => s.lectureId === lecture.id);
                          return (
                            <div 
                              key={lecture.id} 
                              className="grid gap-2 border border-border/30 bg-primary/5 p-4 rounded-3xl transition-all duration-200"
                              onDragOver={(e) => {
                                e.preventDefault();
                                e.currentTarget.classList.add("ring-2", "ring-primary", "ring-offset-2");
                              }}
                              onDragLeave={(e) => {
                                e.currentTarget.classList.remove("ring-2", "ring-primary", "ring-offset-2");
                              }}
                              onDrop={async (e) => {
                                e.preventDefault();
                                e.currentTarget.classList.remove("ring-2", "ring-primary", "ring-offset-2");
                                const sessionId = e.dataTransfer.getData("text/plain");
                                if (sessionId && sessionId !== lecture.id) {
                                  try {
                                    await moveSession(sessionId, lecture.id);
                                    loadSessions(); // refresh the list
                                  } catch (err: any) {
                                    alert("Failed to move session: " + err.message);
                                  }
                                }
                              }}
                            >
                              <div className="flex items-center justify-between pl-2">
                                <div className="flex items-center gap-2 cursor-pointer select-none" onClick={() => toggleLecture(lecture.id)}>
                                  <div className="text-muted-foreground hover:text-foreground transition-colors p-1 -ml-1 rounded-md hover:bg-black/5 dark:hover:bg-white/5">
                                    {collapsedLectures.has(lecture.id) ? <ChevronRight className="h-5 w-5" /> : <ChevronDown className="h-5 w-5" />}
                                  </div>
                                  <div>
                                    <h4 className="font-display font-semibold text-foreground text-lg hover:underline decoration-primary/30 decoration-2 underline-offset-4">{lecture.title || "Untitled Lecture"}</h4>
                                    <p className="text-sm text-muted-foreground font-body">Created {formatRelativeTime(lecture.createdAt)} • {childSessions.length} {childSessions.length === 1 ? 'session' : 'sessions'}</p>
                                  </div>
                                </div>
                                <div className="flex items-center gap-2">
                                  <Button variant="outline" size="sm" onClick={() => onNewSessionFromLecture(lecture.id)} className="gap-2 bg-background">
                                    <Plus className="h-3 w-3" /> Add Session
                                  </Button>
                                  <DropdownMenu>
                                    <DropdownMenuTrigger asChild>
                                      <Button variant="ghost" size="icon" className="text-muted-foreground hover:text-foreground">
                                        <MoreVertical className="h-4 w-4" />
                                      </Button>
                                    </DropdownMenuTrigger>
                                    <DropdownMenuContent align="end" className="w-56 font-body">
                                      <DropdownMenuItem onClick={(e) => handleRenameClick(e as any, lecture.id, lecture.title || "Untitled Lecture")}>
                                        <Pencil className="mr-2 h-4 w-4" /><span>Rename</span>
                                      </DropdownMenuItem>
                                      <DropdownMenuItem onClick={() => onResumeSession(lecture.id)}>
                                        <FileEdit className="mr-2 h-4 w-4" /><span>Edit session settings</span>
                                      </DropdownMenuItem>
                                      <DropdownMenuItem onClick={(e) => handleExportLectureZip(e as any, lecture.id)}>
                                        <Presentation className="mr-2 h-4 w-4" /><span>Export all materials (.zip)</span>
                                      </DropdownMenuItem>
                                      <DropdownMenuItem onClick={(e) => handleDeleteClick(e as any, lecture.id, "LECTURE")} className="text-destructive focus:text-destructive focus:bg-destructive/10">
                                        <Trash2 className="mr-2 h-4 w-4" /><span>Delete Lecture</span>
                                      </DropdownMenuItem>
                                    </DropdownMenuContent>
                                  </DropdownMenu>
                                </div>
                              </div>
                              {!collapsedLectures.has(lecture.id) && childSessions.length > 0 && (
                                <div className="grid gap-2 mt-2">
                                  {childSessions.map(s => renderSessionCard(s, true))}
                                </div>
                              )}
                            </div>
                          );
                        })}
                      </div>
                    </div>
                  )}

                  {standaloneSessions.length > 0 && (
                    <div className="space-y-3">
                      {lectures.length > 0 && (
                        <h3 className="font-display font-semibold text-lg text-foreground flex items-center gap-2 pt-4">
                          <FileText className="h-5 w-5 text-muted-foreground" /> Standalone Sessions
                        </h3>
                      )}
                      <div className="grid gap-3">
                        {standaloneSessions.map(session => renderSessionCard(session))}
                      </div>
                    </div>
                  )}
                </div>
              );
            })()}
          </div>
        )}
      </main>

      {/* Delete Dialog */}
      <AlertDialog open={deletingItem !== null} onOpenChange={(open) => !open && setDeletingItem(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Are you absolutely sure?</AlertDialogTitle>
            <AlertDialogDescription>
              {deletingItem?.type === "LECTURE" 
                ? "This action cannot be undone. This will permanently delete the lecture and ALL sessions within it."
                : "This action cannot be undone. This will permanently delete this session."}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel onClick={() => setDeletingItem(null)}>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={confirmDelete} variant="destructive">Delete</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Rename Dialog */}
      <Dialog open={renamingId !== null} onOpenChange={(open) => !open && setRenamingId(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Rename</DialogTitle>
          </DialogHeader>
          <div className="py-4">
            <Input
              value={renamingTitle}
              onChange={(e) => setRenamingTitle(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") submitRename();
              }}
              autoFocus
            />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setRenamingId(null)}>Cancel</Button>
            <Button onClick={submitRename}>Save</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
