import { getFirestore, type DocumentReference } from "firebase-admin/firestore";
import { getFunctions } from "firebase-admin/functions";
import { defineSecret } from "firebase-functions/params";
import * as logger from "firebase-functions/logger";

/**
 * What every part of the notetaker shares: names, keys, paths, errors and the task queue.
 *
 * THE PIPELINE IN ONE PARAGRAPH. `finalize` marks a recording `uploaded` and queues a task. The task
 * (`ntProcessRecording`, pipeline.ts) joins the chunks into one file, sends it to ElevenLabs, and queues a poll
 * that asks ElevenLabs for the transcript every 30 s to 2 min. A finished transcript queues the task again, which
 * stores it and writes the summary. The sweep (sweep.ts) is the backstop for every step.
 *
 * KEPT APART FROM TAS, which shares this Firebase project: our own collections, our own Storage folder, and every
 * function name starts with `nt`.
 */

export const REGION = "us-central1";
export const RECORDINGS = "notetakerRecordings";
export const USERS = "notetakerUsers";
export const SETTINGS_PATH = "notetakerOps/settings";
export const SWEEP_LEASE_PATH = "notetakerOps/sweep";
const ROOT = "notetaker";

/* The project's existing secrets, reused by name. */
export const ANTHROPIC_API_KEY = defineSecret("ANTHROPIC_API_KEY");
export const OPENAI_API_KEY = defineSecret("OPENAI_API_KEY");
export const ELEVENLABS_API_KEY = defineSecret("ELEVENLABS_API_KEY");
export const ELEVENLABS_WEBHOOK_SECRET = defineSecret("ELEVENLABS_WEBHOOK_SECRET");

/** A real key is long; a placeholder set only so the deploy succeeds is not. */
export function keyIsSet(value: string): boolean {
  return value.trim().length >= 20;
}

/** Where one recording lives in the bucket. Chunks are padded so a listing sorts in order. */
export const paths = {
  folder: (id: string) => `${ROOT}/${id}/`,
  chunks: (id: string) => `${ROOT}/${id}/chunks/`,
  chunk: (id: string, seq: number) => `${ROOT}/${id}/chunks/${String(seq).padStart(5, "0")}.aac`,
  part: (id: string, run: number, n: number) => `${ROOT}/${id}/parts/${run}-${String(n).padStart(2, "0")}.aac`,
  audio: (id: string) => `${ROOT}/${id}/audio.aac`,
  rawTranscript: (id: string) => `${ROOT}/${id}/transcript.raw.json`,
  transcript: (id: string) => `${ROOT}/${id}/transcript.json`,
};

/** `.../chunks/00007.aac` -> 7 */
export const CHUNK_NAME = /\/(\d{5})\.aac$/;

export function recordingRef(id: string): DocumentReference {
  return getFirestore().collection(RECORDINGS).doc(id);
}

export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/* ------------------------------------------------------------------ caller */

/** Who is calling the API, from their ID token and `notetakerUsers/{uid}`. */
export type Caller = {
  uid: string;
  name: string;
  email: string;
  role: "admin" | "member";
  admin: boolean;
};

/* ------------------------------------------------------------------ errors */

const STATUS: Record<string, number> = {
  "invalid-argument": 400,
  unauthenticated: 401,
  "permission-denied": 403,
  "not-found": 404,
  "failed-precondition": 409,
  internal: 500,
  unavailable: 503,
};

/**
 * A refusal the apps translate: sent as `status` + `{ error: key, code: key }`, as the Render API did.
 * `key` is one of TAS's `recorder.err.*` / `recordings.err.*` keys.
 */
export class HttpsError extends Error {
  readonly status: number;
  constructor(readonly code: string, readonly key: string) {
    super(key);
    this.name = "HttpsError";
    this.status = STATUS[code] ?? 500;
  }
}

/** Nothing will change by trying again. The recording is failed at once for an admin to Retry. */
export class PermanentError extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
    this.name = "PermanentError";
  }
}

/** The provider is busy, not broken: queued again after [delaySeconds] without spending an attempt. */
export class BusyError extends Error {
  constructor(readonly code: string, message: string, readonly delaySeconds = 300) {
    super(message);
    this.name = "BusyError";
  }
}

/** The document moved on (retried, deleted, or another task finished first). Not an error. */
export class StaleError extends Error {
  constructor(message = "stale") {
    super(message);
    this.name = "StaleError";
  }
}

/** An error's message, safe to keep: no bodies, no keys, bounded. */
export function describe(err: unknown): string {
  const message = err instanceof Error ? err.message : String(err);
  return message.replace(/\s+/g, " ").slice(0, 500);
}

/** Sorts a provider failure by HTTP status: key and request problems are permanent, the rest are busy. */
export function classifyHttp(status: number | undefined, prefix: string, message: string): Error {
  if (status === 401 || status === 403) {
    return new PermanentError(`${prefix}.auth`, `The ${prefix} key was refused (${status}). ${message}`);
  }
  if (status === 400 || status === 404 || status === 413 || status === 422) {
    return new PermanentError(`${prefix}.rejected`, `The ${prefix} provider refused the request (${status}). ${message}`);
  }
  if (status === 429 || status === 529) {
    return new BusyError(`${prefix}.busy`, `The ${prefix} provider is rate-limiting (${status}).`);
  }
  return new Error(`${prefix} failed (${status ?? "network"}): ${message}`);
}

/* ------------------------------------------------------------------- tasks */

export type PipelineTask = { kind?: "pipeline"; recordingId: string; run: number };
export type ResummarizeTask = { kind: "resummarize"; recordingId: string; summaryId: string };
/** A second sweep of a deleted recording's files, for anything written late. */
export type PurgeTask = { kind: "purge"; recordingId: string; round: number };
/** Asks ElevenLabs whether the transcript is done. */
export type PollTask = { kind: "poll"; recordingId: string; run: number; round: number };
export type RecordingTask = PipelineTask | ResummarizeTask | PurgeTask | PollTask;

/**
 * Queues work for `ntProcessRecording`. The task id is the deduplication: Cloud Tasks refuses a second task with
 * an id it saw in about the last hour, so finalize, the sweep and the poll can all ask for the same step and one
 * task runs. Returns false when the task already existed, which is success.
 */
export async function enqueue(task: RecordingTask, stage: string, delaySeconds = 0): Promise<boolean> {
  const queue = getFunctions().taskQueue<RecordingTask>(`locations/${REGION}/functions/ntProcessRecording`);
  const suffix =
    task.kind === "resummarize" ? task.summaryId
    : task.kind === "purge" ? `p${task.round}`
    : task.kind === "poll" ? `${task.run}-r${task.round}`
    : String(task.run);
  const id = `${task.recordingId}-${suffix}-${stage}`.replace(/[^A-Za-z0-9_-]/g, "_").slice(0, 400);
  try {
    await queue.enqueue(task, {
      id,
      dispatchDeadlineSeconds: 1800,
      ...(delaySeconds > 0 ? { scheduleDelaySeconds: delaySeconds } : {}),
    });
    return true;
  } catch (err) {
    const code = String((err as { code?: string }).code ?? "");
    if (code.endsWith("task-already-exists")) return false;
    logger.error("Could not queue recording work", { recordingId: task.recordingId, stage, error: describe(err) });
    throw err;
  }
}
