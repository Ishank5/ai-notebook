import { getFirestore, type DocumentData } from "firebase-admin/firestore";

import { RECORDINGS, recordingRef, type Caller } from "./common.js";
import { istDate } from "./days.js";
import { noteTypeFromHint, noteTypeOf, type NoteType } from "./notetype.js";

/**
 * Recordings in the caller's scope (members: their own; admins: everyone's), and the compact form of a note the
 * AI features put in their prompts. Single-field queries only, filtered here, so no composite index is needed.
 */

export type Note = { id: string; d: DocumentData };

/** Non-deleted recordings in scope, meeting at or after `from` (epoch ms), newest first. */
export async function recordingsInScope(caller: Caller, from = 0): Promise<Note[]> {
  const recordings = getFirestore().collection(RECORDINGS);
  const snap = caller.admin
    ? await recordings.where("meetingAt", ">=", from).get()
    : await recordings.where("ownerUid", "==", caller.uid).get();
  return snap.docs
    .map((doc) => ({ id: doc.id, d: doc.data() }))
    .filter((n) => n.d.status !== "deleted" && typeof n.d.meetingAt === "number" && n.d.meetingAt >= from)
    .sort((a, b) => b.d.meetingAt - a.d.meetingAt);
}

/** A note with a summary to read: ready, with speech, with a primary summary. */
export function hasSummary(n: Note): boolean {
  return n.d.status === "ready" && !n.d.noSpeech && typeof n.d.summaryId === "string" && n.d.summaryId.length > 0;
}

/** The note's type: from its primary summary, else the phone's hint, else a meeting. */
export function typeOfNote(d: DocumentData): NoteType {
  return noteTypeOf(d.noteType) ?? noteTypeFromHint(d.meetingType) ?? "meeting";
}

/** How long it is, in seconds: the transcript's length, else the phone's. */
export function secondsOf(d: DocumentData): number {
  const audio = d.transcription?.audioSecs;
  if (typeof audio === "number" && audio > 0) return audio;
  return typeof d.durationMs === "number" && d.durationMs > 0 ? d.durationMs / 1000 : 0;
}

export function titleOf(d: DocumentData): string {
  return (typeof d.summaryTitle === "string" && d.summaryTitle) || (typeof d.title === "string" && d.title) || "Untitled note";
}

/** The primary summaries' analyses, by recording id (missing ones left out). */
export async function primaryAnalyses(notes: Note[]): Promise<Map<string, Record<string, unknown>>> {
  const out = new Map<string, Record<string, unknown>>();
  const refs = notes.filter(hasSummary).map((n) => recordingRef(n.id).collection("summaries").doc(n.d.summaryId));
  if (refs.length === 0) return out;
  const snaps = await getFirestore().getAll(...refs);
  for (const snap of snaps) {
    const analysis = snap.get("analysis");
    const recordingId = snap.ref.parent.parent?.id;
    if (recordingId && analysis && typeof analysis === "object") out.set(recordingId, analysis as Record<string, unknown>);
  }
  return out;
}

const arr = (v: unknown): Record<string, unknown>[] =>
  Array.isArray(v) ? v.filter((x): x is Record<string, unknown> => typeof x === "object" && x !== null) : [];
const at = (v: unknown) => (typeof v === "number" && v >= 0 ? v : undefined);

/** Drops empty strings, empty lists and undefined, so the prompt carries only what is there. */
function compact(o: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(Object.entries(o).filter(([, v]) =>
    v !== undefined && v !== null && v !== "" && !(Array.isArray(v) && v.length === 0)));
}

/**
 * One note as the AI features read it: id, title, date, type, who recorded it, summary, key points, decisions,
 * action items, questions and topics, with the moments they were said (`at`, seconds) where known.
 */
export function compactNote(n: Note, analysis: Record<string, unknown> | undefined) {
  const a = analysis ?? {};
  return compact({
    id: n.id,
    title: titleOf(n.d),
    date: typeof n.d.meetingAt === "number" ? istDate(n.d.meetingAt) : undefined,
    type: typeOfNote(n.d),
    recordedBy: n.d.ownerName ?? undefined,
    summary: typeof a.summary === "string" ? a.summary : n.d.summaryText,
    keyPoints: arr(a.key_points).slice(0, 8).map((k) => compact({ text: k.text, at: at(k.at_seconds) })),
    decisions: arr(a.decisions).slice(0, 8).map((k) => compact({ text: k.text, at: at(k.at_seconds) })),
    actionItems: arr(a.action_items).slice(0, 10).map((k) => compact({ task: k.task, owner: k.owner, due: k.due, at: at(k.at_seconds) })),
    questions: arr(a.questions_raised).slice(0, 6).map((k) => compact({ question: k.question, status: k.status, answer: k.answer, at: at(k.at_seconds) })),
    topics: Array.isArray(a.topics) ? a.topics : n.d.topics,
  });
}
