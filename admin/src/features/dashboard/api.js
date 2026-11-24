import { apiFetch } from "../../lib/api";

/** `GET /api/dashboard?days=30`: totals, byDay, byType, moods, topTopics, recent, upcoming. */
export const DASHBOARD_PATH = (days = 30) => `/api/dashboard?days=${days}`;

/** The cached AI digest for a range, or null when there is none yet. */
export const getInsights = (range) => apiFetch(`/api/insights?range=${encodeURIComponent(range)}`).then((d) => d.insights ?? null);

/** Writes the digest for a range now. */
export const generateInsights = (range) => apiFetch("/api/insights", { method: "POST", body: { range } }).then((d) => d.insights ?? null);
