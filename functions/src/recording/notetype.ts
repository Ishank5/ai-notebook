import type { Transcript } from "./transcript.js";

/**
 * Note types and talk time: small pure rules, tested without Firebase.
 *
 * A NOTE IS NOT ALWAYS A MEETING. The phone sends a `meetingType` hint; a clear one decides the type, anything else
 * (`other`, empty, free text) lets the model pick from what was said.
 */

export const NOTE_TYPES = ["meeting", "lecture", "voice_note", "journal"] as const;
export type NoteType = (typeof NOTE_TYPES)[number];

const HINTS: Record<string, NoteType> = {
  lecture: "lecture",
  class: "lecture",
  voice_note: "voice_note",
  voicenote: "voice_note",
  journal: "journal",
  meeting: "meeting",
  team: "meeting",
  team_meeting: "meeting",
  client: "meeting",
  interview: "meeting",
  planning: "meeting",
  project_review: "meeting",
  one_on_one: "meeting",
};

/** The type a phone hint decides, or null when the model should pick. "Voice note", "one-on-one" also match. */
export function noteTypeFromHint(hint: unknown): NoteType | null {
  if (typeof hint !== "string") return null;
  const key = hint.trim().toLowerCase().replace(/[\s-]+/g, "_");
  return HINTS[key] ?? null;
}

/** A stored or requested type, checked: one of the four, else null. */
export function noteTypeOf(value: unknown): NoteType | null {
  return (NOTE_TYPES as readonly unknown[]).includes(value) ? (value as NoteType) : null;
}

export type TalkTime = { speaker: string; seconds: number }[];

/**
 * Seconds spoken per speaker label (S1, S2 ... the labels `participants[].speakers` use), from the turns' start and
 * end times, largest first. A label of "?" (no speaker given) is left out.
 */
export function talkTimeOf(transcript: Pick<Transcript, "turns"> | null | undefined): TalkTime {
  const totals = new Map<string, number>();
  for (const turn of transcript?.turns ?? []) {
    if (!turn || turn.s === "?" || typeof turn.t0 !== "number" || typeof turn.t1 !== "number") continue;
    const secs = Math.max(0, turn.t1 - turn.t0);
    totals.set(turn.s, (totals.get(turn.s) ?? 0) + secs);
  }
  return [...totals.entries()]
    .map(([speaker, secs]) => ({ speaker, seconds: Math.round(secs) }))
    .filter((t) => t.seconds > 0)
    .sort((a, b) => b.seconds - a.seconds || a.speaker.localeCompare(b.speaker, "en", { numeric: true }));
}
