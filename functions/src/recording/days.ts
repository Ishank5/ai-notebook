/**
 * Calendar days in India time (Asia/Kolkata, UTC+5:30, no daylight saving), as "YYYY-MM-DD" strings. Every day
 * boundary in the dashboard, insights and task due dates comes from here.
 */

const IST_OFFSET_MS = 5.5 * 60 * 60 * 1000;
const DAY_MS = 24 * 60 * 60 * 1000;

/** The India-time date of an instant. */
export function istDate(ms: number): string {
  return new Date(ms + IST_OFFSET_MS).toISOString().slice(0, 10);
}

/** Today in India. */
export function istToday(now = Date.now()): string {
  return istDate(now);
}

/** The instant an India-time day starts. */
export function istDayStart(date: string): number {
  return Date.parse(`${date}T00:00:00.000Z`) - IST_OFFSET_MS;
}

/** A date plus (or minus) whole days. */
export function addDays(date: string, days: number): string {
  return new Date(Date.parse(`${date}T00:00:00.000Z`) + days * DAY_MS).toISOString().slice(0, 10);
}

/** 0 = Sunday ... 6 = Saturday, for a date string. */
export function weekdayOf(date: string): number {
  return new Date(`${date}T00:00:00.000Z`).getUTCDay();
}

export const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** A real "YYYY-MM-DD" date, or null. */
export function dateOrNull(value: unknown): string | null {
  if (typeof value !== "string" || !DATE.test(value)) return null;
  const parsed = new Date(`${value}T00:00:00.000Z`);
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value ? value : null;
}
