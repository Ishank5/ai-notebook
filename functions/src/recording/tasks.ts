import { getFirestore, type DocumentData } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";

import { HttpsError, RECORDINGS, describe, recordingRef, type Caller } from "./common.js";
import { DATE, addDays, dateOrNull, istDate, weekdayOf } from "./days.js";
import { noteTypeOf } from "./notetype.js";

/**
 * Tasks: one tracker for every action item (`notetakerTasks/{id}`).
 *
 * FROM THE AI. Whenever a summary becomes a recording's primary, its `action_items` become tasks with the id
 * `${recordingId}_${summaryId}_${i}`. Open AI tasks from an older primary are removed; done ones stay. The recording
 * notes `tasksFor: summaryId`, so `GET /api/tasks` can find (and sync) any recording that was missed.
 *
 * MANUAL tasks belong to whoever added them. Members see and change only their own tasks; admins any.
 * Times are stored and returned as epoch ms.
 */

export const TASKS = "notetakerTasks";
const PRIORITIES = ["high", "medium", "low"] as const;
type Priority = (typeof PRIORITIES)[number];
const TASK_ID = /^[A-Za-z0-9_-]{1,200}$/;

export type TaskDoc = {
  ownerUid: string;
  ownerName: string;
  recordingId: string | null;
  summaryId: string | null;
  sourceIndex: number | null;
  text: string;
  owner: string;
  due: string;
  dueDate: string | null;
  priority: Priority;
  done: boolean;
  doneAt: number | null;
  atSeconds: number | null;
  source: "ai" | "manual";
  createdAt: number;
  updatedAt: number;
};

/** What a task row shows of its recording. */
type RecordingInfo = { title: string | null; meetingAt: number | null };

/* -------------------------------------------------------------- pure rules */

export function aiTaskId(recordingId: string, summaryId: string, index: number): string {
  return `${recordingId}_${summaryId}_${index}`;
}

/**
 * When `summaryId` becomes the primary: which tasks to remove (open AI tasks of another summary) and which of the
 * wanted ids to create (those not there yet, so a ticked task keeps its tick when the same summary syncs again).
 */
export function planTaskSync(
  existing: { id: string; source?: unknown; summaryId?: unknown; done?: unknown }[],
  wantedIds: string[],
  summaryId: string,
): { create: string[]; remove: string[] } {
  const have = new Set(existing.map((t) => t.id));
  return {
    remove: existing.filter((t) => t.source === "ai" && t.summaryId !== summaryId && t.done !== true).map((t) => t.id),
    create: wantedIds.filter((id) => !have.has(id)),
  };
}

const WEEKDAYS = ["sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"];
const MONTHS = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"];
const NUMBERS: Record<string, number> = {
  a: 1, an: 1, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10,
  fifteen: 15, thirty: 30,
};

function dateFrom(year: number, month: number, day: number): string | null {
  return dateOrNull(`${year}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`);
}

/** A day and month without a year: this year, or next year if that is well past already. */
function nearest(base: string, month: number, day: number, year?: number): string | null {
  if (year) return dateFrom(year, month, day);
  const y = Number(base.slice(0, 4));
  const date = dateFrom(y, month, day);
  return date && date < addDays(base, -60) ? dateFrom(y + 1, month, day) : date;
}

/**
 * The model's free-text `due` ("by Friday", "tomorrow", "in two weeks", "28 Nov") as a date, counted from the
 * meeting's day in India. Anything vague ("soon", "next sprint") is null.
 */
export function dueDateOf(due: unknown, meetingAt: number | null, now = Date.now()): string | null {
  if (typeof due !== "string") return null;
  const t = due.toLowerCase().replace(/[,.]/g, " ").replace(/\s+/g, " ").trim();
  if (!t) return null;
  const base = istDate(meetingAt ?? now);
  const wd = weekdayOf(base);
  let m: RegExpExecArray | null;

  if ((m = /\b(\d{4})-(\d{2})-(\d{2})\b/.exec(t))) return dateOrNull(m[0]);

  const year = /\b(20\d{2})\b/.exec(t)?.[1];
  if ((m = /\b(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\b/.exec(t))) {
    return nearest(base, MONTHS.indexOf(m[2]) + 1, Number(m[1]), year ? Number(year) : undefined);
  }
  if ((m = /\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\s+(\d{1,2})(?:st|nd|rd|th)?\b/.exec(t))) {
    return nearest(base, MONTHS.indexOf(m[1]) + 1, Number(m[2]), year ? Number(year) : undefined);
  }
  // Day first, as written in India: 28/11, 28-11-2026.
  if ((m = /\b(\d{1,2})[/-](\d{1,2})(?:[/-](\d{2}|\d{4}))?\b/.exec(t))) {
    const y = m[3] ? (m[3].length === 2 ? 2000 + Number(m[3]) : Number(m[3])) : undefined;
    return nearest(base, Number(m[2]), Number(m[1]), y);
  }

  if (/\bday after tomorrow\b/.test(t)) return addDays(base, 2);
  if (/\b(today|tonight|end of (the )?day|eod)\b/.test(t)) return base;
  if (/\btomorrow\b/.test(t)) return addDays(base, 1);

  if ((m = /\b(?:in|within|after)\s+(\d{1,3}|[a-z]+)\s+(day|week|month)s?\b/.exec(t))) {
    const n = /^\d+$/.test(m[1]) ? Number(m[1]) : NUMBERS[m[1]];
    if (n) return addDays(base, n * (m[2] === "day" ? 1 : m[2] === "week" ? 7 : 30));
  }
  if (/\b(end of (the )?week|this week|eow)\b/.test(t)) return addDays(base, (5 - wd + 7) % 7);
  if (/\bnext week\b/.test(t)) return addDays(base, (1 - wd + 7) % 7 || 7);
  if (/\b(end of (the )?month|this month)\b/.test(t)) {
    const [y, mo] = base.split("-").map(Number);
    return addDays(dateFrom(mo === 12 ? y + 1 : y, mo === 12 ? 1 : mo + 1, 1) as string, -1);
  }

  const day = WEEKDAYS.findIndex((name) => new RegExp(`\\b${name}\\b`).test(t));
  if (day >= 0) return addDays(base, (day - wd + 7) % 7 || 7);
  return null;
}

/* ------------------------------------------------------------------- shape */

function str(v: unknown, max: number): string {
  return typeof v === "string" ? v.trim().slice(0, max) : "";
}

function taskOut(id: string, d: DocumentData, info: Map<string, RecordingInfo>) {
  const rec = typeof d.recordingId === "string" ? info.get(d.recordingId) : undefined;
  return {
    id,
    ownerUid: String(d.ownerUid ?? ""),
    ownerName: String(d.ownerName ?? ""),
    recordingId: d.recordingId ?? null,
    summaryId: d.summaryId ?? null,
    sourceIndex: typeof d.sourceIndex === "number" ? d.sourceIndex : null,
    text: String(d.text ?? ""),
    owner: String(d.owner ?? ""),
    due: String(d.due ?? ""),
    dueDate: typeof d.dueDate === "string" && DATE.test(d.dueDate) ? d.dueDate : null,
    priority: (PRIORITIES as readonly unknown[]).includes(d.priority) ? d.priority : "medium",
    done: d.done === true,
    doneAt: typeof d.doneAt === "number" ? d.doneAt : null,
    atSeconds: typeof d.atSeconds === "number" ? d.atSeconds : null,
    source: d.source === "ai" ? "ai" : "manual",
    createdAt: typeof d.createdAt === "number" ? d.createdAt : 0,
    updatedAt: typeof d.updatedAt === "number" ? d.updatedAt : 0,
    recordingTitle: rec?.title ?? null,
    meetingAt: rec?.meetingAt ?? null,
  };
}
export type TaskRow = ReturnType<typeof taskOut>;

/** Open first, then by due date (none last), then newest. */
function byUrgency(a: TaskRow, b: TaskRow): number {
  if (a.done !== b.done) return a.done ? 1 : -1;
  if (a.dueDate !== b.dueDate) {
    if (!a.dueDate) return 1;
    if (!b.dueDate) return -1;
    return a.dueDate < b.dueDate ? -1 : 1;
  }
  return b.createdAt - a.createdAt;
}

function infoOf(d: DocumentData): RecordingInfo {
  return {
    title: (typeof d.summaryTitle === "string" && d.summaryTitle) || (typeof d.title === "string" && d.title) || null,
    meetingAt: typeof d.meetingAt === "number" ? d.meetingAt : null,
  };
}

/* -------------------------------------------------------------------- sync */

/**
 * Makes `summaryId` the source of a recording's AI tasks (see the top of the file), and copies the primary's
 * type, topics and action count onto the recording (so older recordings get them too).
 */
export async function syncRecordingTasks(
  recordingId: string,
  summaryId: string,
  analysis: Record<string, unknown> | null | undefined,
  rec: DocumentData,
): Promise<void> {
  if (rec.status === "deleted") return;
  const db = getFirestore();
  const tasks = db.collection(TASKS);
  const items = Array.isArray(analysis?.action_items) ? (analysis.action_items as Record<string, unknown>[]).slice(0, 100) : [];
  const meetingAt = typeof rec.meetingAt === "number" ? rec.meetingAt : null;

  const existing = await tasks.where("recordingId", "==", recordingId).get();
  const wanted = items.map((item, i) => ({ id: aiTaskId(recordingId, summaryId, i), item, i }));
  const plan = planTaskSync(existing.docs.map((d) => ({ id: d.id, ...d.data() })), wanted.map((w) => w.id), summaryId);
  const create = new Set(plan.create);

  const batch = db.batch();
  for (const id of plan.remove) batch.delete(tasks.doc(id));
  const now = Date.now();
  for (const { id, item, i } of wanted) {
    if (!create.has(id) || !str(item?.task, 1000)) continue;
    const doc: TaskDoc = {
      ownerUid: String(rec.ownerUid ?? ""),
      ownerName: String(rec.ownerName ?? ""),
      recordingId,
      summaryId,
      sourceIndex: i,
      text: str(item.task, 1000),
      owner: str(item.owner, 200),
      due: str(item.due, 200),
      dueDate: dueDateOf(item.due, meetingAt, now),
      priority: (PRIORITIES as readonly unknown[]).includes(item.priority) ? (item.priority as Priority) : "medium",
      done: false,
      doneAt: null,
      atSeconds: typeof item.at_seconds === "number" && item.at_seconds >= 0 ? Math.floor(item.at_seconds) : null,
      source: "ai",
      createdAt: now,
      updatedAt: now,
    };
    batch.set(tasks.doc(id), doc);
  }
  batch.update(recordingRef(recordingId), {
    tasksFor: summaryId,
    noteType: noteTypeOf(analysis?.note_type) ?? "meeting",
    topics: Array.isArray(analysis?.topics) ? (analysis.topics as unknown[]).filter((t) => typeof t === "string").slice(0, 6) : [],
    actionCount: items.length,
  });
  await batch.commit();
}

/** Syncs a recording from whatever its primary summary is now. Never throws: the lazy backfill catches up. */
export async function syncFromPrimary(recordingId: string): Promise<void> {
  try {
    const ref = recordingRef(recordingId);
    const rec = (await ref.get()).data();
    if (!rec || rec.status !== "ready" || typeof rec.summaryId !== "string" || !rec.summaryId) return;
    const summary = await ref.collection("summaries").doc(rec.summaryId).get();
    await syncRecordingTasks(recordingId, rec.summaryId, summary.get("analysis") ?? null, rec);
  } catch (err) {
    logger.error("Tasks not synced", { recordingId, error: describe(err) });
  }
}

/** A deleted recording's tasks go with it. */
export async function deleteRecordingTasks(recordingId: string): Promise<void> {
  const snap = await getFirestore().collection(TASKS).where("recordingId", "==", recordingId).get();
  for (let i = 0; i < snap.docs.length; i += 400) {
    const batch = getFirestore().batch();
    snap.docs.slice(i, i + 400).forEach((d) => batch.delete(d.ref));
    await batch.commit();
  }
}

/** Most recordings synced per request, so one slow page load cannot become a long one. */
const BACKFILL_MAX = 25;

/**
 * The lazy backfill: syncs ready recordings in scope whose `tasksFor` is not their primary. Returns what task rows
 * show of each ready recording in scope (single-field queries only, so no index is needed).
 */
export async function backfillTasks(caller: Caller): Promise<Map<string, RecordingInfo>> {
  const recordings = getFirestore().collection(RECORDINGS);
  const snap = caller.admin
    ? await recordings.where("status", "==", "ready").get()
    : await recordings.where("ownerUid", "==", caller.uid).get();
  const info = new Map<string, RecordingInfo>();
  const stale: string[] = [];
  for (const doc of snap.docs) {
    const d = doc.data();
    if (d.status !== "ready") continue;
    info.set(doc.id, infoOf(d));
    if (typeof d.summaryId === "string" && d.summaryId && d.tasksFor !== d.summaryId && !d.noSpeech) stale.push(doc.id);
  }
  await Promise.all(stale.slice(0, BACKFILL_MAX).map((id) => syncFromPrimary(id)));
  return info;
}

/** Every task in scope as rows, most urgent first. */
export async function tasksInScope(caller: Caller, info: Map<string, RecordingInfo>): Promise<TaskRow[]> {
  const tasks = getFirestore().collection(TASKS);
  const snap = caller.admin ? await tasks.get() : await tasks.where("ownerUid", "==", caller.uid).get();
  return snap.docs.map((d) => taskOut(d.id, d.data(), info)).sort(byUrgency);
}

/* ------------------------------------------------------------------ routes */

/** `GET /api/tasks?status=open|done|all` → `{ tasks }` */
export async function listTasks(caller: Caller, query: Record<string, unknown>) {
  const status = query.status === "open" || query.status === "done" ? query.status : "all";
  const rows = await tasksInScope(caller, await backfillTasks(caller));
  return { tasks: rows.filter((t) => status === "all" || (status === "done" ? t.done : !t.done)) };
}

function priorityIn(v: unknown, fallback: Priority | null): Priority {
  if (v === undefined || v === null || v === "") {
    if (fallback) return fallback;
    throw new HttpsError("invalid-argument", "recordings.err.badRequest");
  }
  if (!(PRIORITIES as readonly unknown[]).includes(v)) throw new HttpsError("invalid-argument", "recordings.err.badRequest");
  return v as Priority;
}

/** null for "no date"; a bad date is refused. */
function dueDateIn(v: unknown): string | null {
  if (v === undefined || v === null || v === "") return null;
  const date = dateOrNull(v);
  if (!date) throw new HttpsError("invalid-argument", "recordings.err.badRequest");
  return date;
}

/** `POST /api/tasks` `{ text, dueDate?, priority?, owner? }` → `{ task }`: a manual task of the caller's. */
export async function createTask(caller: Caller, body: Record<string, unknown>) {
  const text = str(body.text, 1000);
  if (!text) throw new HttpsError("invalid-argument", "recordings.err.badRequest");
  const now = Date.now();
  const doc: TaskDoc = {
    ownerUid: caller.uid,
    ownerName: caller.name,
    recordingId: null,
    summaryId: null,
    sourceIndex: null,
    text,
    owner: str(body.owner, 200) || caller.name,
    due: "",
    dueDate: dueDateIn(body.dueDate),
    priority: priorityIn(body.priority, "medium"),
    done: false,
    doneAt: null,
    atSeconds: null,
    source: "manual",
    createdAt: now,
    updatedAt: now,
  };
  const ref = getFirestore().collection(TASKS).doc();
  await ref.set(doc);
  return { task: taskOut(ref.id, doc, new Map()) };
}

/** A task the caller may change, or "not found". */
async function ownTask(caller: Caller, rawId: unknown) {
  if (typeof rawId !== "string" || !TASK_ID.test(rawId)) throw new HttpsError("not-found", "recordings.err.notFound");
  const ref = getFirestore().collection(TASKS).doc(rawId);
  const d = (await ref.get()).data();
  if (!d || (!caller.admin && d.ownerUid !== caller.uid)) throw new HttpsError("not-found", "recordings.err.notFound");
  return { ref, d };
}

/** `PATCH /api/tasks/:id` `{ done?, text?, owner?, dueDate?, priority? }` → `{ task }` */
export async function updateTask(caller: Caller, rawId: unknown, body: Record<string, unknown>) {
  const { ref, d } = await ownTask(caller, rawId);
  const now = Date.now();
  const patch: Record<string, unknown> = { updatedAt: now };
  if (body.done !== undefined) {
    if (typeof body.done !== "boolean") throw new HttpsError("invalid-argument", "recordings.err.badRequest");
    patch.done = body.done;
    patch.doneAt = body.done ? (d.done === true && typeof d.doneAt === "number" ? d.doneAt : now) : null;
  }
  if (body.text !== undefined) {
    const text = str(body.text, 1000);
    if (!text) throw new HttpsError("invalid-argument", "recordings.err.badRequest");
    patch.text = text;
  }
  if (body.owner !== undefined) patch.owner = str(body.owner, 200);
  if (body.dueDate !== undefined) patch.dueDate = dueDateIn(body.dueDate);
  if (body.priority !== undefined) patch.priority = priorityIn(body.priority, null);
  await ref.update(patch);

  const info = new Map<string, RecordingInfo>();
  if (typeof d.recordingId === "string") {
    const rec = (await recordingRef(d.recordingId).get()).data();
    if (rec) info.set(d.recordingId, infoOf(rec));
  }
  return { task: taskOut(ref.id, { ...d, ...patch }, info) };
}

/** `DELETE /api/tasks/:id` → `{ ok: true }` */
export async function deleteTask(caller: Caller, rawId: unknown) {
  const { ref } = await ownTask(caller, rawId);
  await ref.delete();
  return { ok: true };
}
