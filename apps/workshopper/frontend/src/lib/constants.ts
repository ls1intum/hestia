export const phaseEmojis: Record<string, string> = {
  ARRIVE: "👋",
  ACTIVATE: "💡",
  INFORM: "🗣️",
  PROCESS: "🛠️",
  BREAK: "☕",
  EVALUATE: "✅",
  SUMMARY: "🏁",
  LEARNING_CYCLE: "🎯",
  CUSTOM: "✏️",
  BUFFER: "⏳",
};

export const phaseRowColors: Record<string, string> = {
  ARRIVE: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-primary",
  ACTIVATE: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-accent",
  INFORM: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-primary/80",
  PROCESS: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-accent/80",
  BREAK: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-muted-foreground",
  EVALUATE: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-primary/60",
  SUMMARY: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-accent/60",
  LEARNING_CYCLE: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-primary",
  CUSTOM: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-border",
  BUFFER: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-muted",
};

export const getStepEmoji = (text: string) => {
  const t = text.toLowerCase();
  if (t.includes("lecture") || t.includes("presentation") || t.includes("explain") || t.includes("concept")) return "🧑‍🏫";
  if (t.includes("prompt") || t.includes("question") || t.includes("q&a") || t.includes("quiz")) return "❓";
  if (t.includes("activity") || t.includes("exercise") || t.includes("practice")) return "🛠️";
  if (t.includes("discuss") || t.includes("debate") || t.includes("share")) return "💬";
  if (t.includes("brainstorm") || t.includes("ideate")) return "💡";
  if (t.includes("review") || t.includes("feedback") || t.includes("evaluate")) return "✅";
  if (t.includes("welcome") || t.includes("intro")) return "👋";
  if (t.includes("wrap") || t.includes("summary") || t.includes("conclusion")) return "🏁";
  if (t.includes("break") || t.includes("pause")) return "☕";
  if (t.includes("read") || t.includes("case study")) return "📖";
  if (t.includes("video") || t.includes("watch")) return "🎥";
  if (t.includes("role play") || t.includes("simulate")) return "🎭";
  if (t.includes("icebreaker") || t.includes("game")) return "🎲";
  return "✨";
};

export const getStepColorClass = (text: string) => {
  return "bg-card border-border/60";
};

export const DEFAULT_ACTIVITIES = [
  "Group Discussion", "Case Study", "Role Play",
  "Hands-on Practice", "Quiz / Polls", "Q&A Session",
  "Peer Review", "Brainstorming", "Think-Pair-Share",
];

export const getMechanicDescription = (mechanic: string) => {
  const m = String(mechanic).toLowerCase();
  if (m.includes("think-pair-share") || m.includes("think pair share")) return "A three-step structure: individuals first reflect alone, then discuss with a partner, then share with the wider group. Balances independent thinking with collaborative discussion.";
  if (m.includes("brainstorm")) return "A free-flowing idea-generation session where quantity and creativity are prioritized over immediate judgment. Useful for problem-solving and innovation.";
  if (m.includes("role play") || m.includes("role-play")) return "Participants act out defined roles or scenarios to practice skills, explore perspectives, or simulate real-life interactions in a low-stakes environment. Great for building empathy and interpersonal skills.";
  if (m.includes("case study")) return "An in-depth analysis of a real or realistic scenario, where participants examine context, decisions, and outcomes to extract practical lessons. Ideal for applying theory to real-world situations.";
  if (m.includes("group discussion") || m.includes("discussion")) return "An open conversation among participants to explore a topic collaboratively, share perspectives, and build on each other's ideas. Best for surfacing diverse viewpoints and encouraging active listening.";
  if (m.includes("hands-on practice") || m.includes("practice")) return "A guided, practical exercise where participants directly apply a skill or tool themselves rather than just observing. Reinforces learning through doing and immediate feedback.";
  if (m.includes("quiz") || m.includes("poll")) return "Short, structured questions used to check understanding, gather opinions, or gauge the room in real time. Quick to run and useful for engagement or knowledge checks.";
  if (m.includes("peer review") || m.includes("peer feedback")) return "Participants evaluate and give constructive feedback on each other's work. Builds critical thinking and exposes people to different approaches and standards.";
  if (m.includes("q&a") || m.includes("questions") || m.includes("q & a")) return "A dedicated segment where participants can ask questions and receive direct answers from a facilitator or expert. Clarifies doubts and encourages open dialogue.";
  if (m.includes("world cafe")) return "A structured conversational process where participants rotate between small table groups to discuss different aspects of a central theme.";
  if (m.includes("jigsaw")) return "Participants become experts in one aspect of a topic in a specialized group, then re-form into new groups to teach their peers what they learned.";
  if (m.includes("fishbowl")) return "A small group discusses a topic in a central circle while the rest of the participants observe from an outer circle, occasionally swapping places.";
  if (m.includes("gallery walk")) return "Participants walk around the room to view, discuss, and add comments to charts or posters created by different groups.";
  if (m.includes("icebreaker")) return "A short, interactive activity designed to help participants get to know each other and feel comfortable in the group.";
  if (m.includes("snowball")) return "Individuals pair up to discuss an idea, then join another pair to form a group of four, continuing to double in size to synthesize ideas.";
  return "A structured pedagogical activity designed to engage participants, encourage interaction, and facilitate active learning of the workshop material.";
};
