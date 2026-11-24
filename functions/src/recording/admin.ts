import { FieldValue, Timestamp, getFirestore, type DocumentData } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import * as logger from "firebase-functions/logger";

import {
  BusyError,
  CHUNK_NAME,
  ELEVENLABS_API_KEY,
  ELEVENLABS_WEBHOOK_SECRET,
  HttpsError,
  PermanentError,
  SETTINGS_PATH,
  UUID,
  describe,
  enqueue,
  keyIsSet,
  paths,
  recordingRef,
  type Caller,
} from "./common.js";
import { TRANSCRIBE_MODEL } from "./elevenlabs.js";
import { SUMMARY_MODELS, estimateCostUsd, modelById } from "./models.js";
import { noteTypeOf, type NoteType } from "./notetype.js";
import { SAMPLE_RECORDED_BY, SAMPLE_TITLE, SAMPLE_TRANSCRIPT } from "./sample.js";
import { readSettings } from "./settings.js";
import { listFieldsOf, ms, readJson, runOf, wipeRecordingContent } from "./store.js";
import { providerKeyIsSet, runDirect } from "./summary/index.js";
import { SUMMARY_LANGUAGES, buildPrompt, type SummaryLanguage } from "./summary/prompt.js";
import { deleteRecordingTasks, syncFromPrimary } from "./tasks.js";
import type { Transcript } from "./transcript.js";

/**
 * The admin's tools and the media route, as plain functions `(caller, ...)` that ntApi calls (api.ts decides who
 * may call which). These were TAS's callables; requests and answers are unchanged.
 *
 * EVERY LISTEN, TRANSCRIPT VIEW, DOWNLOAD AND DELETION IS LOGGED under the recording (`access`), with who and when.
 */

/** An hour: long enough to listen to a whole meeting, short enough to be useless when copied. */
const LINK_TTL_MS = 60 * 60 * 1000;

function idOf(raw: unknown): string {
  if (typeof raw !== "string" || !UUID.test(raw)) throw new HttpsError("not-found", "recordings.err.notFound");
  return raw.toLowerCase();
}

function languageOf(raw: unknown, fallback: SummaryLanguage): SummaryLanguage {
  return (SUMMARY_LANGUAGES as readonly unknown[]).includes(raw) ? (raw as SummaryLanguage) : fallback;
}

async function logAccess(recordingId: string, caller: Caller, action: string): Promise<void> {
  await recordingRef(recordingId).collection("access").add({
    uid: caller.uid,
    name: caller.name,
    action,
    at: FieldValue.serverTimestamp(),
  });
}

/* ---------------------------------------------------------------- settings */

/**
 * Reads the settings, or saves new ones (`{ set: { summaryModel, language? } }`) and reads them back.
 * A model not in models.ts, or whose provider has no key, cannot be saved.
 */
export async function recordingSettings(caller: Caller, data: Record<string, unknown>) {
  const set = data.set as Record<string, unknown> | undefined;

  if (set && typeof set === "object") {
    const current = await readSettings();
    const model = modelById(set.summaryModel ?? current.summaryModel);
    if (!model) throw new HttpsError("invalid-argument", "recordings.err.unknownModel");
    if (!providerKeyIsSet(model.provider)) throw new HttpsError("failed-precondition", "recordings.err.noKey");
    const language = languageOf(set.language, "en");
    await getFirestore().doc(SETTINGS_PATH).set({
      summaryModel: model.id,
      language,
      updatedAt: FieldValue.serverTimestamp(),
      updatedBy: caller.uid,
      updatedByName: caller.name,
    });
    logger.info("Recording settings changed", { by: caller.uid, model: model.id, language });
  }

  const settings = await readSettings();
  return {
    settings: {
      summaryModel: settings.summaryModel,
      language: settings.language,
      updatedAt: settings.updatedAt ? settings.updatedAt.toMillis() : null,
      updatedByName: settings.updatedByName,
    },
    models: SUMMARY_MODELS.map((m) => ({
      id: m.id,
      provider: m.provider,
      label: m.label,
      price: m.price,
      note: m.note,
      available: providerKeyIsSet(m.provider),
    })),
    keys: {
      anthropic: providerKeyIsSet("anthropic"),
      openai: providerKeyIsSet("openai"),
      elevenlabs: keyIsSet(ELEVENLABS_API_KEY.value()),
      webhook: keyIsSet(ELEVENLABS_WEBHOOK_SECRET.value()),
    },
    transcription: { provider: "ElevenLabs", model: TRANSCRIBE_MODEL },
  };
}

/** Runs the sample meeting through a model, now, and returns what it wrote. */
export async function testSummaryModel(_caller: Caller, data: Record<string, unknown>) {
  const model = modelById(data.model);
  if (!model) throw new HttpsError("invalid-argument", "recordings.err.unknownModel");

  const prompt = buildPrompt({
    recordedBy: SAMPLE_RECORDED_BY,
    meetingAt: Date.now(),
    audioSecs: SAMPLE_TRANSCRIPT.audioSecs,
    title: SAMPLE_TITLE,
    transcript: SAMPLE_TRANSCRIPT,
    language: languageOf(data.language, "en"),
  });
  const started = Date.now();
  const seconds = () => Math.round((Date.now() - started) / 100) / 10;
  try {
    const outcome = await runDirect(model, prompt, SAMPLE_TRANSCRIPT.audioSecs);
    if (outcome.kind === "done") {
      return {
        ok: true,
        model: model.id,
        seconds: seconds(),
        analysis: outcome.analysis,
        usage: outcome.usage,
        costUsd: estimateCostUsd(model, outcome.usage),
      };
    }
    return { ok: false, model: model.id, seconds: seconds(), code: outcome.code, message: outcome.message };
  } catch (err) {
    const code = err instanceof PermanentError || err instanceof BusyError ? err.code : "summary.error";
    return { ok: false, model: model.id, seconds: seconds(), code, message: describe(err) };
  }
}

/* ----------------------------------------------------------------- actions */

/** `{ action: "retry" | "process" | "resummarize" | "setPrimary" | "delete", ... }` on one recording. */
export async function recordingAction(caller: Caller, rawId: unknown, data: Record<string, unknown>) {
  const recordingId = idOf(rawId);
  switch (data.action) {
    case "retry":
      return retry(recordingId, caller, data.fresh === true);
    case "process":
      return processWhatArrived(recordingId, caller);
    case "resummarize":
      return resummarize(recordingId, caller, data.model, data.language, data.noteType);
    case "setPrimary":
      return setPrimary(recordingId, caller, data.summaryId);
    case "delete":
      return deleteRecording(recordingId, caller);
    default:
      throw new HttpsError("invalid-argument", "recordings.err.badRequest");
  }
}

/** A processing run that has not moved for this long can be retried as if it had failed. */
const STUCK_MS = 30 * 60 * 1000;

/**
 * Processes a failed (or stuck) recording again, from where it failed. The joined audio and a transcript that
 * arrived are reused; `fresh` throws the transcript away and sends the audio again.
 */
async function retry(recordingId: string, caller: Caller, fresh: boolean) {
  const ref = recordingRef(recordingId);
  const run = await getFirestore().runTransaction(async (tx) => {
    const d = (await tx.get(ref)).data();
    if (!d) throw new HttpsError("not-found", "recordings.err.notFound");
    const stuck = d.status === "processing" && Date.now() - (ms(d.stepAt) ?? 0) > STUCK_MS;
    if (d.status !== "failed" && !stuck) throw new HttpsError("failed-precondition", "recordings.err.notFailed");

    const next = runOf(d) + 1;
    const patch: Record<string, unknown> = {
      status: "uploaded",
      run: next,
      step: FieldValue.delete(),
      error: FieldValue.delete(),
      summaryJob: FieldValue.delete(),
      resummarize: FieldValue.delete(),
      waits: 0,
      nudges: 0,
      retriedAt: Timestamp.now(),
      retriedBy: caller.uid,
      retriedByName: caller.name,
    };
    if (fresh || !d.transcription?.receivedAt) patch.transcription = FieldValue.delete();
    tx.update(ref, patch);
    return next;
  });

  if (fresh) {
    const bucket = getStorage().bucket();
    await Promise.all([paths.rawTranscript(recordingId), paths.transcript(recordingId)].map((p) =>
      bucket.file(p).delete({ ignoreNotFound: true })));
  }
  await enqueue({ recordingId, run }, "start");
  logger.info("Recording retried", { recordingId, run, by: caller.uid, fresh });
  return { ok: true, run };
}

/** Processes a recording the phone never finished sending, with whatever chunks arrived. */
async function processWhatArrived(recordingId: string, caller: Caller) {
  const ref = recordingRef(recordingId);
  const [files] = await getStorage().bucket().getFiles({ prefix: paths.chunks(recordingId) });
  const chunks = files
    .map((f) => ({ match: CHUNK_NAME.exec(f.name), bytes: Number(f.metadata.size ?? 0) }))
    .filter((c): c is { match: RegExpExecArray; bytes: number } => c.match !== null)
    .map((c) => ({ seq: Number(c.match[1]), bytes: c.bytes }))
    .sort((a, b) => a.seq - b.seq);
  if (chunks.length === 0) throw new HttpsError("failed-precondition", "recordings.err.nothingUploaded");

  const run = await getFirestore().runTransaction(async (tx) => {
    const d = (await tx.get(ref)).data();
    if (!d) throw new HttpsError("not-found", "recordings.err.notFound");
    if (d.status !== "uploading") throw new HttpsError("failed-precondition", "recordings.err.notUploading");
    const next = typeof d.run === "number" ? d.run + 1 : 1;
    tx.update(ref, {
      status: "uploaded",
      run: next,
      chunks: chunks.map((c) => c.seq),
      chunkCount: chunks.length,
      bytes: chunks.reduce((sum, c) => sum + c.bytes, 0),
      meetingAt: typeof d.meetingAt === "number" ? d.meetingAt : (d.startedAt ?? ms(d.createdAt) ?? Date.now()),
      forcedAt: Timestamp.now(),
      forcedBy: caller.uid,
      forcedByName: caller.name,
      uploadedAt: Timestamp.now(),
    });
    return next;
  });
  await enqueue({ recordingId, run }, "start");
  logger.info("Recording processed with what arrived", { recordingId, run, chunks: chunks.length, by: caller.uid });
  return { ok: true, run, chunks: chunks.length };
}

/** A requested note type: none (`undefined`, empty, "auto": the phone's hint or the model decides), or one of four. */
function requestedNoteType(raw: unknown): NoteType | null {
  if (raw === undefined || raw === null || raw === "" || raw === "auto") return null;
  const type = noteTypeOf(raw);
  if (!type) throw new HttpsError("invalid-argument", "recordings.err.badRequest");
  return type;
}

/**
 * "Summarise again with this model", stored beside the other summaries. With a `noteType` ("write this as a
 * lecture") the new summary also becomes the primary when it is done: that is a correction, not a comparison.
 */
async function resummarize(recordingId: string, caller: Caller, rawModel: unknown, rawLanguage: unknown, rawNoteType: unknown) {
  const model = modelById(rawModel);
  if (!model) throw new HttpsError("invalid-argument", "recordings.err.unknownModel");
  const noteType = requestedNoteType(rawNoteType);
  if (!providerKeyIsSet(model.provider)) throw new HttpsError("failed-precondition", "recordings.err.noKey");
  const language = languageOf(rawLanguage, (await readSettings()).language);

  const ref = recordingRef(recordingId);
  const summaryId = ref.collection("summaries").doc().id;
  await getFirestore().runTransaction(async (tx) => {
    const d = (await tx.get(ref)).data();
    if (!d) throw new HttpsError("not-found", "recordings.err.notFound");
    if (d.status !== "ready" || !d.transcription?.savedAt || d.noSpeech) {
      throw new HttpsError("failed-precondition", "recordings.err.notReady");
    }
    if (d.resummarize?.state === "running") throw new HttpsError("failed-precondition", "recordings.err.busy");
    tx.update(ref, {
      resummarize: {
        summaryId,
        model: model.id,
        language,
        noteType,
        state: "running",
        requestedAt: Timestamp.now(),
        requestedBy: caller.uid,
        requestedByName: caller.name,
      },
    });
  });
  try {
    await enqueue({ kind: "resummarize", recordingId, summaryId }, "resummarize");
  } catch (err) {
    // Not queued, so nothing would ever finish it: say so now.
    await ref.update({
      "resummarize.state": "failed",
      "resummarize.error": { code: "summary.notQueued", message: describe(err) },
      "resummarize.finishedAt": Timestamp.now(),
    }).catch(() => undefined);
    throw new HttpsError("unavailable", "recordings.err.unknown");
  }
  return { ok: true, summaryId };
}

/** Makes one of a recording's summaries the one the list shows. */
async function setPrimary(recordingId: string, caller: Caller, rawSummaryId: unknown) {
  if (typeof rawSummaryId !== "string" || !/^[A-Za-z0-9]{1,40}$/.test(rawSummaryId)) {
    throw new HttpsError("invalid-argument", "recordings.err.badRequest");
  }
  const ref = recordingRef(recordingId);
  const summary = await ref.collection("summaries").doc(rawSummaryId).get();
  if (!summary.exists) throw new HttpsError("not-found", "recordings.err.notFound");
  await getFirestore().runTransaction(async (tx) => {
    const d = (await tx.get(ref)).data();
    if (!d || d.status !== "ready") throw new HttpsError("failed-precondition", "recordings.err.notReady");
    tx.update(ref, {
      ...listFieldsOf(summary.get("analysis"), summary.id, String(summary.get("model") ?? "")),
      primaryChosenBy: caller.uid,
      primaryChosenByName: caller.name,
      primaryChosenAt: Timestamp.now(),
    });
  });
  await syncFromPrimary(recordingId);
  return { ok: true };
}

/** When the deleted folder is swept again: after any upload link given out has expired, and later still. */
const PURGE_DELAYS_SECS = [20 * 60, 2 * 60 * 60];

/**
 * Deletes a recording's audio, transcripts and summaries for good. The document stays as a tombstone (who
 * recorded it, when, who deleted it, its access log) so a phone still holding chunks is told it is closed rather
 * than creating it again. The run moves on first, so no older task can write to it.
 */
async function deleteRecording(recordingId: string, caller: Caller) {
  const ref = recordingRef(recordingId);
  await getFirestore().runTransaction(async (tx) => {
    const existing = (await tx.get(ref)).data();
    if (!existing) throw new HttpsError("not-found", "recordings.err.notFound");
    if (existing.status === "deleted") return;
    tx.update(ref, {
      status: "deleted",
      run: runOf(existing) + 1,
      deletedAt: Timestamp.now(),
      deletedBy: caller.uid,
      deletedByName: caller.name,
      ...Object.fromEntries([
        "step", "audio", "transcription", "summaryJob", "resummarize", "error",
        "summaryId", "summaryModel", "summaryTitle", "summaryText", "mood", "meetingKind", "noSpeech",
        "noteType", "topics", "actionCount", "talkTime", "tasksFor",
        "title", "meetingType", "location", "attendees",
      ].map((field) => [field, FieldValue.delete()])),
    });
  });

  await wipeRecordingContent(ref);
  await deleteRecordingTasks(recordingId).catch((err) =>
    logger.warn("Tasks of a deleted recording not removed", { recordingId, error: describe(err) }));
  for (const [round, delay] of PURGE_DELAYS_SECS.entries()) {
    await enqueue({ kind: "purge", recordingId, round: round + 1 }, "purge", delay).catch((err) =>
      logger.warn("Follow-up sweep of a deleted recording not queued", { recordingId, error: describe(err) }));
  }

  await logAccess(recordingId, caller, "delete");
  logger.info("Recording deleted", { recordingId, by: caller.uid });
  return { ok: true };
}

/* ------------------------------------------------------------------- media */

const MEDIA_KINDS = ["listen", "download-audio", "transcript", "download-transcript"] as const;

/**
 * The audio as a one-hour signed link, or the transcript itself, for the recording's owner or an admin. Somebody
 * else's recording is "not found": whether it exists is not theirs to know.
 */
export async function recordingMedia(caller: Caller, rawId: unknown, data: Record<string, unknown>) {
  const recordingId = idOf(rawId);
  const kind = data.kind as (typeof MEDIA_KINDS)[number];
  if (!MEDIA_KINDS.includes(kind)) throw new HttpsError("invalid-argument", "recordings.err.badRequest");

  const d = (await recordingRef(recordingId).get()).data();
  if (!d || d.status === "deleted" || (!caller.admin && d.ownerUid !== caller.uid)) {
    throw new HttpsError("not-found", "recordings.err.notFound");
  }

  if (kind === "listen" || kind === "download-audio") {
    if (!d.audio?.path) throw new HttpsError("failed-precondition", "recordings.err.noAudio");
    const [url] = await getStorage().bucket().file(d.audio.path).getSignedUrl({
      version: "v4",
      action: "read",
      expires: Date.now() + LINK_TTL_MS,
      responseType: "audio/aac",
      ...(kind === "download-audio"
        ? { responseDisposition: `attachment; filename="${fileNameOf(recordingId, d)}.aac"` }
        : {}),
    });
    await logAccess(recordingId, caller, kind);
    return { url, expiresAt: Date.now() + LINK_TTL_MS };
  }

  if (!d.transcription?.savedAt) throw new HttpsError("failed-precondition", "recordings.err.noTranscript");
  const transcript = await readJson<Transcript>(paths.transcript(recordingId));
  await logAccess(recordingId, caller, kind);
  return { transcript };
}

/** `meeting-2026-09-25-meera-iyer`, ASCII only; a name with no Latin letters falls back to the id. */
function fileNameOf(recordingId: string, d: DocumentData): string {
  const when = typeof d.meetingAt === "number" ? new Date(d.meetingAt + 5.5 * 3600 * 1000).toISOString().slice(0, 10) : "";
  const who = String(d.ownerName ?? "")
    .normalize("NFKD")
    .replace(/[^A-Za-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .toLowerCase()
    .slice(0, 40);
  return ["meeting", when, who || recordingId.slice(0, 8)].filter(Boolean).join("-");
}

/* ---------------------------------------------------------------- progress */

/** How much of each still-uploading recording has reached the bucket, and when the last piece arrived. */
export async function recordingUploadProgress(_caller: Caller, data: Record<string, unknown>) {
  const ids = data.ids;
  if (!Array.isArray(ids) || ids.length > 50) throw new HttpsError("invalid-argument", "recordings.err.badRequest");
  const bucket = getStorage().bucket();
  const progress: Record<string, { chunks: number; bytes: number; lastAt: number | null }> = {};
  await Promise.all(ids.map(async (raw) => {
    if (typeof raw !== "string" || !UUID.test(raw)) throw new HttpsError("invalid-argument", "recordings.err.badRequest");
    const id = raw.toLowerCase();
    const [files] = await bucket.getFiles({ prefix: paths.chunks(id) });
    let bytes = 0;
    let lastAt = 0;
    for (const f of files) {
      bytes += Number(f.metadata.size ?? 0);
      const at = Date.parse(String(f.metadata.updated ?? f.metadata.timeCreated ?? ""));
      if (Number.isFinite(at)) lastAt = Math.max(lastAt, at);
    }
    progress[raw] = { chunks: files.length, bytes, lastAt: lastAt || null };
  }));
  return { progress };
}
