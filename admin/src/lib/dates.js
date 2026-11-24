/** Calendar days as "YYYY-MM-DD" in local time, and the few labels the screens need. */

const pad = (n) => String(n).padStart(2, "0");

/** A Date (or epoch ms) as its local "YYYY-MM-DD". */
export function dayKey(value = new Date()) {
  const d = value instanceof Date ? value : new Date(value);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** "YYYY-MM-DD" as a local Date at midnight. */
export function parseDay(key) {
  const [y, m, d] = String(key).split("-").map(Number);
  return new Date(y, (m || 1) - 1, d || 1);
}

export function addDays(key, n) {
  const d = parseDay(key);
  d.setDate(d.getDate() + n);
  return dayKey(d);
}

/** Whole days from a to b (b later is positive). */
export function daysBetween(a, b) {
  return Math.round((parseDay(b) - parseDay(a)) / 86400000);
}

const SHORT = new Intl.DateTimeFormat("en-IN", { day: "numeric", month: "short", numberingSystem: "latn" });
const WEEKDAY = new Intl.DateTimeFormat("en-IN", { weekday: "short", day: "numeric", month: "short", numberingSystem: "latn" });
const LONG = new Intl.DateTimeFormat("en-IN", { weekday: "long", day: "numeric", month: "long", numberingSystem: "latn" });
const DATE_TIME = new Intl.DateTimeFormat("en-IN", { day: "numeric", month: "short", hour: "numeric", minute: "2-digit", numberingSystem: "latn" });

/** "24 Nov". */
export const shortDay = (key) => SHORT.format(parseDay(key));
/** "Tue, 24 Nov". */
export const weekdayDay = (key) => WEEKDAY.format(parseDay(key));
/** "Tuesday, 24 November". */
export const longToday = () => LONG.format(new Date());
/** "24 Nov, 2:05 pm" from epoch ms. */
export const shortDateTime = (ms) => (Number.isFinite(ms) ? DATE_TIME.format(new Date(ms)) : "");

/** A due date said the way people say it: "Today", "Tomorrow", "2 days late", "Fri, 28 Nov". */
export function dueLabel(dueDate, today = dayKey()) {
  if (!dueDate) return "";
  const diff = daysBetween(today, dueDate);
  if (diff === 0) return "Today";
  if (diff === 1) return "Tomorrow";
  if (diff === -1) return "Yesterday";
  if (diff < 0) return `${-diff} days late`;
  return weekdayDay(dueDate);
}

/** "just now", "5 min ago", "3 h ago", "2 days ago", then the date. */
export function timeAgo(ms) {
  if (!Number.isFinite(ms)) return "";
  const secs = Math.max(0, (Date.now() - ms) / 1000);
  if (secs < 60) return "just now";
  if (secs < 3600) return `${Math.floor(secs / 60)} min ago`;
  if (secs < 86400) return `${Math.floor(secs / 3600)} h ago`;
  const days = Math.floor(secs / 86400);
  if (days < 7) return days === 1 ? "yesterday" : `${days} days ago`;
  return SHORT.format(new Date(ms));
}

/** "Good morning" by the clock. */
export function greeting() {
  const h = new Date().getHours();
  if (h < 12) return "Good morning";
  if (h < 17) return "Good afternoon";
  return "Good evening";
}
