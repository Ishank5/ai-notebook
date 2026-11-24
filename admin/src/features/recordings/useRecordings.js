import { useEffect, useState } from "react";
import { poll } from "../../lib/poll";

/** A ceiling on rows the API returns, kept in step with the server. */
export const MAX_ROWS = 1500;

/** Every 5 s while a recording on screen is still uploading or processing, else every 15 s. */
const FAST_MS = 5000;
const SLOW_MS = 15000;
const SETTLED = new Set(["ready", "failed", "deleted"]);
const working = (r) => Boolean(r) && (!SETTLED.has(r.status) || r.resummarize?.state === "running");

/**
 * Every recording whose meeting falls in a date range, newest first, polled every 5 s while one is
 * still working, else every 15 s.
 * The API returns everyone's to an admin and the caller's own to a member.
 * The person, status and search filters run over this in memory, so changing one is instant.
 */
export function useRecordings({ fromMillis, toMillis }) {
  const [state, setState] = useState({ key: null, status: "loading", rows: [], truncated: false });
  const [attempt, setAttempt] = useState(0);
  const key = `${fromMillis}-${toMillis}-${attempt}`;

  useEffect(() => poll(
    `/api/recordings?from=${fromMillis}&to=${toMillis}`,
    (data) => setState({ key, status: "ready", rows: data.rows ?? [], truncated: Boolean(data.truncated) }),
    // An error, never an empty list: a refused read and a day without meetings mean opposite things.
    () => setState({ key, status: "error", rows: [], truncated: false }),
    (data) => ((data?.rows ?? []).some(working) ? FAST_MS : SLOW_MS),
  ), [fromMillis, toMillis, key]);

  // Rows from the last range stay on screen while the new range loads.
  const status = state.key === key ? state.status : "loading";
  return { status, rows: state.rows, truncated: state.truncated, reload: () => setAttempt((n) => n + 1) };
}

/** One recording, its summaries and (for an admin) its access log, polled so processing shows without a refresh. */
export function useRecording(recordingId) {
  const [state, setState] = useState({ id: null, status: "loading", recording: null, summaries: [], access: [] });

  useEffect(() => poll(
    `/api/recordings/${encodeURIComponent(recordingId)}`,
    (data) => setState({ id: recordingId, status: "ready", recording: data.recording, summaries: data.summaries ?? [], access: data.access ?? [] }),
    (err) => setState({ id: recordingId, status: err?.status === 404 ? "missing" : "error", recording: null, summaries: [], access: [] }),
    (data) => (working(data?.recording) ? FAST_MS : SLOW_MS),
  ), [recordingId]);

  // Another recording's page is never shown while this one loads.
  return state.id === recordingId ? state : { status: "loading", recording: null, summaries: [], access: [] };
}
