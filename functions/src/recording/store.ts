import {
  FieldValue,
  Timestamp,
  getFirestore,
  type DocumentData,
  type DocumentReference,
  type UpdateData,
} from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import * as logger from "firebase-functions/logger";

import { StaleError, describe, paths } from "./common.js";
import { estimateCostUsd, resolveModel } from "./models.js";
import { noteTypeFromHint, noteTypeOf } from "./notetype.js";
import type { Usage } from "./summary/outcome.js";
import type { Analysis, PromptInput, SummaryLanguage } from "./summary/prompt.js";
import type { Transcript } from "./transcript.js";

/**
 * Reading and writing a recording's processing state, safely.
 *
 * EVERY WRITE CHECKS THE RUN. A recording is processed in numbered runs: the
 * first when it is uploaded, another each time an admin presses Retry, and a
 * jump when it is deleted. A task, a webhook or a sweep carrying yesterday's
 * run number finds the document has moved on and changes nothing — which is
 * what stops a slow transcript from an abandoned attempt overwriting a newer
 * one, or anything at all writing to a deleted recording.
 */

export type Gap = { startMs: number; endMs: number | null; reason: string };

export type TranscriptionState = {
  provider?: string;
  model?: string;
  requestId?: string | null;
  transcriptionId?: string | null;
  claimedAt?: Timestamp;
  requestedAt?: Timestamp;
  receivedAt?: Timestamp;
  receivedVia?: string;
  polledAt?: Timestamp;
  savedAt?: Timestamp;
};

export type SummaryJob = {
  summaryId: string;
  provider: "anthropic" | "openai";
  model: string;
  language: SummaryLanguage;
  /**
   * "running" for every new job. A document from before the batch API was removed may still say "queued" or
   * "submitted" and carry `mode` or `batchId`; the pipeline writes such a summary directly.
   */
  state: string;
  attempts: number;
  queuedAt?: Timestamp;
  /** Set when an earlier answer was cut off; see `boostedMaxTokens`. */
  maxTokens?: number;
  lastError?: { code: string; message: string };
};

export type Recording = DocumentData & {
  id: string;
  ownerUid: string;
  ownerName?: string | null;
  status: string;
  step?: "join" | "transcribe" | "summarize" | null;
  run?: number;
  chunks?: number[];
  meetingAt?: number | null;
  startedAt?: number | null;
  title?: string | null;
  meetingType?: string | null;
  location?: string | null;
  attendees?: string | null;
  gaps?: Gap[];
  audio?: { path: string; bytes: number } | null;
  transcription?: TranscriptionState | null;
  summaryJob?: SummaryJob | null;
  stepAt?: Timestamp;
  waits?: number;
  nudges?: number;
};

export function runOf(d: DocumentData | undefined): number {
  return typeof d?.run === "number" ? d.run : 1;
}

export function ms(v: unknown): number | null {
  return v instanceof Timestamp ? v.toMillis() : null;
}

export function asRecording(id: string, d: DocumentData): Recording {
  return { ...d, id } as Recording;
}

/**
 * Applies [patch] only if the recording is still on [run], still processing,
 * and — when given — still at [step]. Throws StaleError otherwise.
 */
export async function guardedUpdate(
  ref: DocumentReference,
  run: number,
  patch: UpdateData<DocumentData>,
  step?: string,
): Promise<void> {
  await getFirestore().runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const d = snap.data();
    if (!d || runOf(d) !== run || d.status !== "processing" || (step !== undefined && d.step !== step)) {
      throw new StaleError();
    }
    tx.update(ref, patch);
  });
}

/**
 * Marks a run failed, with a code the portal can explain and a message an
 * admin can act on. A no-op if the run is already over.
 */
export async function failRun(
  ref: DocumentReference,
  run: number,
  step: string | null | undefined,
  code: string,
  message: string,
): Promise<void> {
  const failed = await getFirestore().runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const d = snap.data();
    if (!d || runOf(d) !== run || !["uploaded", "processing"].includes(d.status)) return false;
    tx.update(ref, {
      status: "failed",
      error: { code, message: message.slice(0, 500), step: step ?? d.step ?? null, at: Timestamp.now() },
      failedAt: Timestamp.now(),
      summaryJob: FieldValue.delete(),
    });
    return true;
  });
  if (failed) logger.error("Recording failed", { recordingId: ref.id, run, step, code, message });
}

/* ----------------------------------------------------------------- storage */

/**
 * Everything stored for a recording except its document and access log: the
 * audio, the chunks, both transcripts and every summary. Used by deletion,
 * and again later by the purge task, for anything written by work that was
 * already running when the recording was deleted.
 */
export async function wipeRecordingContent(ref: DocumentReference): Promise<void> {
  await getStorage().bucket().deleteFiles({ prefix: paths.folder(ref.id), force: true });
  const summaries = await ref.collection("summaries").listDocuments();
  for (let i = 0; i < summaries.length; i += 400) {
    const batch = getFirestore().batch();
    summaries.slice(i, i + 400).forEach((s) => batch.delete(s));
    await batch.commit();
  }
}

export async function readJson<T>(path: string): Promise<T> {
  const [bytes] = await getStorage().bucket().file(path).download();
  return JSON.parse(bytes.toString("utf8")) as T;
}

export async function saveJson(path: string, value: unknown): Promise<void> {
  await getStorage().bucket().file(path).save(JSON.stringify(value), {
    contentType: "application/json",
    resumable: false,
  });
}

/* ----------------------------------------------------------------- prompts */

/** Everything the summary prompt needs about one recording. */
export async function promptInputFor(rec: Recording, language: SummaryLanguage): Promise<PromptInput> {
  const transcript = await readJson<Transcript>(paths.transcript(rec.id));
  return {
    recordedBy: rec.ownerName ?? "",
    meetingAt: rec.meetingAt ?? rec.startedAt ?? null,
    audioSecs: transcript.audioSecs,
    title: rec.title,
    meetingType: rec.meetingType,
    noteType: noteTypeFromHint(rec.meetingType),
    location: rec.location,
    attendees: rec.attendees,
    gaps: rec.gaps,
    transcript,
    language,
  };
}

/* --------------------------------------------------------------- summaries */

/**
 * What the list (and the dashboard) shows of a summary, copied onto the recording itself. Read loosely: a summary
 * from before note types has no type (it is a meeting), topics or mind map.
 */
export function listFieldsOf(analysis: Analysis, summaryId: string, model: string) {
  const a = (analysis ?? {}) as Partial<Analysis>;
  return {
    summaryId,
    summaryModel: model,
    summaryTitle: String(a.title ?? "").slice(0, 200),
    summaryText: String(a.summary ?? "").slice(0, 2000),
    mood: a.mood ?? "neutral",
    meetingKind: a.meeting_kind || null,
    noteType: noteTypeOf(a.note_type) ?? "meeting",
    topics: Array.isArray(a.topics) ? a.topics.filter((t) => typeof t === "string").slice(0, 6) : [],
    actionCount: Array.isArray(a.action_items) ? a.action_items.length : 0,
  };
}

/**
 * Stores the pipeline's summary and marks the recording ready, in one
 * transaction — or nothing, if the run or the job moved on meanwhile.
 */
export async function saveRunSummary(
  ref: DocumentReference,
  run: number,
  job: SummaryJob,
  result: { analysis: Analysis; usage: Usage; stopReason: string },
): Promise<void> {
  const model = resolveModel(job.model);
  await getFirestore().runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const d = snap.data();
    if (
      !d || runOf(d) !== run || d.status !== "processing" ||
      d.summaryJob?.summaryId !== job.summaryId
    ) {
      throw new StaleError();
    }
    const now = Timestamp.now();
    tx.set(ref.collection("summaries").doc(job.summaryId), {
      provider: model.provider,
      model: model.id,
      mode: "direct",
      language: job.language,
      run,
      requestedBy: null,
      requestedByName: null,
      createdAt: now,
      analysis: result.analysis,
      usage: result.usage,
      stopReason: result.stopReason,
      costUsd: estimateCostUsd(model, result.usage),
    });
    tx.update(ref, {
      status: "ready",
      step: null,
      stepAt: now,
      readyAt: now,
      summaryJob: FieldValue.delete(),
      error: FieldValue.delete(),
      ...listFieldsOf(result.analysis, job.summaryId, model.id),
    });
  });
  logger.info("Recording ready", { recordingId: ref.id, run, model: model.id, ...result.usage });
}

/** Swallows StaleError, which only ever means "somebody else already moved on". */
export async function quietly(work: () => Promise<unknown>, context: Record<string, unknown>): Promise<void> {
  try {
    await work();
  } catch (err) {
    if (err instanceof StaleError) return;
    logger.error("Recording update failed", { ...context, error: describe(err) });
  }
}
