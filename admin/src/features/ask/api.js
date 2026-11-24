import { apiFetch } from "../../lib/api";

/**
 * `POST /api/ask`: an answer from the notes in scope, with citations.
 * history: the last turns as [{ role, content }], at most 6.
 */
export const askNotes = ({ question, recordingId, history }) =>
  apiFetch("/api/ask", {
    method: "POST",
    body: { question, ...(recordingId ? { recordingId } : {}), history: (history ?? []).slice(-6) },
  });
