/**
 * Number and date formatting for the Recordings screens: `en-IN` with Latin
 * digits, dates as "24 Nov 2026", date-times as "24 Nov 2026, 2:05 pm", "-" for nothing.
 */
const FORMAT_LOCALE = "en-IN";
const LANG = "en";

const numberCache = new Map();
const dateCache = new Map();

function numberFormatter(options) {
  const key = JSON.stringify(options ?? {});
  if (!numberCache.has(key)) numberCache.set(key, new Intl.NumberFormat(FORMAT_LOCALE, { numberingSystem: "latn", ...options }));
  return numberCache.get(key);
}

function dateFormatter(options) {
  const key = JSON.stringify(options);
  if (!dateCache.has(key)) dateCache.set(key, new Intl.DateTimeFormat(FORMAT_LOCALE, { numberingSystem: "latn", ...options }));
  return dateCache.get(key);
}

function toDate(value) {
  if (!value) return null;
  if (value instanceof Date) return value;
  if (typeof value.toDate === "function") return value.toDate();
  if (typeof value === "number") return new Date(value);
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

const formatNumber = (value, options) => (Number.isFinite(value) ? numberFormatter(options).format(value) : "-");

function formatDate(value) {
  const d = toDate(value);
  return d ? dateFormatter({ day: "numeric", month: "short", year: "numeric" }).format(d) : "-";
}

function formatDateTime(value) {
  const d = toDate(value);
  return d ? dateFormatter({ day: "numeric", month: "short", year: "numeric", hour: "numeric", minute: "2-digit" }).format(d) : "-";
}

function pickLocalized(value, lang) {
  if (value == null) return "";
  if (typeof value === "string") return value;
  return value[lang] || value.en || Object.values(value).find(Boolean) || "";
}

const FORMAT = {
  lang: LANG,
  number: (v, o) => formatNumber(v, o),
  date: (v) => formatDate(v),
  dateTime: (v) => formatDateTime(v),
  localized: (v) => pickLocalized(v, LANG),
};

/** Kept a hook so the screens read the same as before. */
export function useFormat() {
  return FORMAT;
}
