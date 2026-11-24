import type { Caller } from "./common.js";
import { addDays, istDate, istDayStart, istToday } from "./days.js";
import { NOTE_TYPES, type NoteType } from "./notetype.js";
import { recordingsInScope, secondsOf, titleOf, typeOfNote } from "./notes.js";
import { recordingOut } from "./read.js";
import { backfillTasks, tasksInScope } from "./tasks.js";

/**
 * `GET /api/dashboard?days=30`: totals, activity per day, note types, moods, recurring topics, recent notes and
 * upcoming tasks, for the caller's scope. Days are India-time days; "this week" is the last 7 days, today included.
 */

const minutes = (secs: number) => Math.round((secs / 60) * 10) / 10;

export async function dashboard(caller: Caller, query: Record<string, unknown>) {
  const asked = Math.floor(Number(query.days));
  const days = Number.isFinite(asked) && asked >= 1 ? Math.min(asked, 365) : 30;
  const today = istToday();
  const firstDay = addDays(today, -(days - 1));
  const weekFirst = addDays(today, -6);
  const windowFrom = istDayStart(firstDay);
  const weekFrom = istDayStart(weekFirst);

  const [all, info] = await Promise.all([
    recordingsInScope(caller, Math.min(windowFrom, weekFrom)),
    backfillTasks(caller),
  ]);
  const tasks = await tasksInScope(caller, info);
  const inWindow = all.filter((n) => n.d.meetingAt >= windowFrom);
  const inWeek = all.filter((n) => n.d.meetingAt >= weekFrom);

  // Activity per day, every day in the window.
  const perDay = new Map<string, { notes: number; secs: number }>();
  for (let i = 0; i < days; i++) perDay.set(addDays(firstDay, i), { notes: 0, secs: 0 });
  for (const n of inWindow) {
    const day = perDay.get(istDate(n.d.meetingAt));
    if (day) {
      day.notes++;
      day.secs += secondsOf(n.d);
    }
  }

  const byType = Object.fromEntries(NOTE_TYPES.map((t) => [t, 0])) as Record<NoteType, number>;
  for (const n of inWindow) byType[typeOfNote(n.d)]++;

  const ready = inWindow.filter((n) => n.d.status === "ready" && !n.d.noSpeech);
  const moods = ready
    .filter((n) => typeof n.d.mood === "string" && n.d.summaryId)
    .map((n) => ({ date: istDate(n.d.meetingAt), mood: n.d.mood as string, recordingId: n.id, title: titleOf(n.d), meetingAt: n.d.meetingAt as number }))
    .sort((a, b) => a.meetingAt - b.meetingAt);

  // Recurring topics, grouped case-insensitively, shown as first written.
  const topics = new Map<string, { topic: string; count: number; recordingIds: string[] }>();
  for (const n of ready) {
    const seen = new Set<string>();
    for (const raw of Array.isArray(n.d.topics) ? n.d.topics : []) {
      if (typeof raw !== "string" || !raw.trim()) continue;
      const key = raw.trim().toLowerCase();
      if (seen.has(key)) continue;
      seen.add(key);
      const entry = topics.get(key) ?? { topic: raw.trim(), count: 0, recordingIds: [] };
      entry.count++;
      entry.recordingIds.push(n.id);
      topics.set(key, entry);
    }
  }
  const topTopics = [...topics.values()].sort((a, b) => b.count - a.count || a.topic.localeCompare(b.topic)).slice(0, 10);

  const open = tasks.filter((t) => !t.done);
  const soon = addDays(today, 7);
  const byDue = (a: { dueDate: string | null }, b: { dueDate: string | null }) =>
    (a.dueDate as string) < (b.dueDate as string) ? -1 : (a.dueDate as string) > (b.dueDate as string) ? 1 : 0;
  // The 3 most recently overdue, then what is due in the next 7 days: a long overdue tail never hides what is next.
  const overdue = open.filter((t) => t.dueDate !== null && t.dueDate < today).sort(byDue).slice(-3);
  const next = open.filter((t) => t.dueDate !== null && t.dueDate >= today && t.dueDate <= soon).sort(byDue);
  const upcoming = [...overdue, ...next].slice(0, 8);

  return {
    totals: {
      notes: inWindow.length,
      minutes: minutes(inWindow.reduce((sum, n) => sum + secondsOf(n.d), 0)),
      notesThisWeek: inWeek.length,
      minutesThisWeek: minutes(inWeek.reduce((sum, n) => sum + secondsOf(n.d), 0)),
      openTasks: open.length,
      doneTasks: tasks.length - open.length,
      overdueTasks: open.filter((t) => t.dueDate !== null && t.dueDate < today).length,
    },
    byDay: [...perDay.entries()].map(([date, v]) => ({ date, notes: v.notes, minutes: minutes(v.secs) })),
    byType,
    moods,
    topTopics,
    recent: inWindow.slice(0, 6).map((n) => recordingOut(n.id, n.d)),
    upcoming,
  };
}
