import { apiFetch } from "../../lib/api";
import { refreshAll } from "../../lib/poll";

/**
 * The Recordings calls to ntApi. Audio, transcripts and keys only ever come through here:
 * the bucket and the API keys never reach this browser, and every listen, transcript read
 * and download is written to the recording's access log on the way.
 */
const post = (path, body) => apiFetch(path, { method: "POST", body: body ?? {} });
const recording = (id) => `/api/recordings/${encodeURIComponent(id)}`;

/** After an action, everything on screen reloads at once rather than at the next poll. */
async function act(id, body) {
  const result = await post(`${recording(id)}/action`, body);
  refreshAll();
  return result;
}

export const getSettings = () => apiFetch("/api/recordings/settings");
export const saveSettings = (set) => post("/api/recordings/settings", { set });
export const testModel = (model) => post("/api/recordings/test-model", { model });

export const retryRecording = (recordingId, fresh = false) => act(recordingId, { action: "retry", fresh });
export const processWhatArrived = (recordingId) => act(recordingId, { action: "process" });
/** Summarise again, optionally with another model or as another note type. */
export const resummarize = (recordingId, model, noteType) =>
  act(recordingId, { action: "resummarize", ...(model ? { model } : {}), ...(noteType ? { noteType } : {}) });
export const setPrimarySummary = (recordingId, summaryId) => act(recordingId, { action: "setPrimary", summaryId });
export const deleteRecording = (recordingId) => act(recordingId, { action: "delete" });

/** `listen` | `download-audio` → { url }; `transcript` | `download-transcript` → { transcript }. */
export const media = (recordingId, kind) => post(`${recording(recordingId)}/media`, { kind });
/** A follow-up email drafted from the note: `{ subject, body }`. */
export const draftEmail = (recordingId, tone) => post(`${recording(recordingId)}/email`, { tone });
export const uploadProgress = (ids) => post("/api/recordings/upload-progress", { ids });

/**
 * A refusal as a string key. The API answers with a `recordings.err.*` code, or `forbidden`
 * when this login may not do it at all; anything else (no network, server down) is "try again".
 */
export function errorKey(error) {
  const code = String(error?.code ?? "");
  const match = /^recordings\.err\.([A-Za-z]+)$/.exec(code);
  if (match) return `err.${match[1]}`;
  if (code === "forbidden") return "err.notAllowed";
  return "err.unknown";
}
