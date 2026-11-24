import { BookOpen, GraduationCap, Mic, Users } from "lucide-react";

import { cn } from "./ui";

/** The four kinds of note, in the order the screens list them. */
// eslint-disable-next-line react-refresh/only-export-components
export const NOTE_TYPES = [
  { id: "meeting", label: "Meeting", icon: Users, className: "bg-primary-tint text-primary" },
  { id: "lecture", label: "Lecture", icon: GraduationCap, className: "bg-lecture-tint text-lecture" },
  { id: "voice_note", label: "Voice note", icon: Mic, className: "bg-voice-tint text-voice" },
  { id: "journal", label: "Journal", icon: BookOpen, className: "bg-journal-tint text-journal" },
];

const BY_ID = Object.fromEntries(NOTE_TYPES.map((n) => [n.id, n]));

/** The type of a list row or a recording with its summary: the copied field first, then the analysis. */
// eslint-disable-next-line react-refresh/only-export-components
export function noteTypeOf(recording, analysis) {
  const id = recording?.noteType ?? analysis?.note_type ?? analysis?.noteType;
  return BY_ID[id] ? id : null;
}

// eslint-disable-next-line react-refresh/only-export-components
export const noteTypeLabel = (id) => BY_ID[id]?.label ?? "Note";

/** A small badge with the type's icon and word. Nothing for an unknown type. */
export function TypeBadge({ type, size, className }) {
  const n = BY_ID[type];
  if (!n) return null;
  const Icon = n.icon;
  return (
    <span className={cn(
      "inline-flex shrink-0 items-center gap-1 whitespace-nowrap rounded-full font-semibold",
      size === "sm" ? "h-6 px-2 text-[11px]" : "h-7 px-2.5 text-xs",
      n.className,
      className,
    )}>
      <Icon className={size === "sm" ? "size-3" : "size-3.5"} aria-hidden="true" />
      {n.label}
    </span>
  );
}

/** The type's icon in a soft round tile, for list rows. */
export function TypeIcon({ type, className }) {
  const n = BY_ID[type] ?? { icon: Mic, className: "bg-surface-alt text-ink-muted" };
  const Icon = n.icon;
  return (
    <span className={cn("flex size-9 shrink-0 items-center justify-center rounded-xl", n.className, className)} aria-hidden="true">
      <Icon className="size-4" />
    </span>
  );
}
