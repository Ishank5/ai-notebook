import { FieldValue, Timestamp, getFirestore, type DocumentReference } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { onTaskDispatched } from "firebase-functions/v2/tasks";
import * as logger from "firebase-functions/logger";

import {
  ANTHROPIC_API_KEY,
  BusyError,
  ELEVENLABS_API_KEY,
  OPENAI_API_KEY,
  PermanentError,
  REGION,
  StaleError,
  describe,
  enqueue,
  keyIsSet,
  paths,
  recordingRef,
  type PipelineTask,
  type PollTask,
  type PurgeTask,
  type RecordingTask,
  type ResummarizeTask,
} from "./common.js";
import { TRANSCRIBE_MODEL, fetchTranscript, requestTranscription, type TranscriptionRequest } from "./elevenlabs.js";
import { deleteChunks, joinChunks } from "./join.js";
import {
  MAX_SUMMARY_ATTEMPTS,
  boostedMaxTokens,
  estimateCostUsd,
  modelById,
  resolveModel,
} from "./models.js";
import { noteTypeOf, talkTimeOf } from "./notetype.js";
import { acceptTranscript } from "./receive.js";
import { readSettings } from "./settings.js";
import {
  asRecording,
  failRun,
  guardedUpdate,
  listFieldsOf,
  ms,
  promptInputFor,
  quietly,
  readJson,
  runOf,
  saveJson,
  saveRunSummary,
  wipeRecordingContent,
  type Recording,
} from "./store.js";
import { runDirect } from "./summary/index.js";
import { buildPrompt, withNoteType } from "./summary/prompt.js";
import { syncFromPrimary, syncRecordingTasks } from "./tasks.js";
import { normalizeTranscript } from "./transcript.js";

/**
 * `ntProcessRecording`: one recording, moved as far along as it can go now.
 *
 * A STATE MACHINE ON THE DOCUMENT. `step` says where the recording is — join,
 * transcribe, summarize — and each step writes its result before moving `step`
 * on. A task can therefore die at any line and the next one, whoever queued
 * it, carries on from the last thing written rather than from the start. A
 * step whose output already exists is never paid for twice.
 *
 * THE TASK STOPS WHEN THERE IS NOTHING TO DO BUT WAIT. After sending the audio
 * to ElevenLabs it queues a poll task and returns; the poll (or the sweep)
 * queues it again when the transcript is in. The summary is then written
 * directly, in the same task.
 *
 * FAILING LOUDLY. Five attempts with backoff for anything unexpected; a
 * provider that is merely busy is re-queued later without spending one; and a
 * permanent failure — a refused key, unreadable audio — marks the recording
 * failed at once, with the reason, for an admin to Retry.
 */

const MAX_ATTEMPTS = 5;
/** 36 re-queues of five minutes: three hours of a busy provider before giving up. */
const MAX_WAITS = 36;
/** A claim to send the audio that was never followed up — the task died — expires. */
const CLAIM_TTL_MS = 15 * 60 * 1000;

export const ntProcessRecording = onTaskDispatched<RecordingTask>(
  {
    region: REGION,
    retryConfig: {
      maxAttempts: MAX_ATTEMPTS,
      minBackoffSeconds: 60,
      maxBackoffSeconds: 1800,
      maxDoublings: 3,
    },
    /*
     * Six at once. The work of a task is seconds of joining and uploading; the
     * cap is for the providers behind it, which an evening's 300 recordings
     * arriving together would otherwise hit all at the same moment.
     */
    rateLimits: { maxConcurrentDispatches: 6, maxDispatchesPerSecond: 2 },
    // A summary written directly can take minutes.
    timeoutSeconds: 1800,
    memory: "1GiB",
    secrets: [ELEVENLABS_API_KEY, ANTHROPIC_API_KEY, OPENAI_API_KEY],
  },
  async (request) => {
    const task = request.data;
    if (!task || typeof task.recordingId !== "string") {
      logger.warn("Recording task without a recording", { id: request.id });
      return;
    }
    const lastAttempt = request.retryCount >= MAX_ATTEMPTS - 1;
    if (task.kind === "resummarize") {
      await resummarize(task, lastAttempt);
    } else if (task.kind === "purge") {
      await purge(task);
    } else if (task.kind === "poll") {
      await poll(task);
    } else {
      await pipeline({ recordingId: task.recordingId, run: Number(task.run) || 1 }, lastAttempt);
    }
  },
);

/* ---------------------------------------------------------------- pipeline */

async function pipeline(task: PipelineTask, lastAttempt: boolean): Promise<void> {
  const ref = recordingRef(task.recordingId);
  let rec = await begin(ref, task.run);
  if (!rec) return;

  try {
    if (rec.step === "join") rec = await stepJoin(ref, rec);
    if (rec.step === "transcribe") {
      const next = await stepTranscribe(ref, rec);
      if (!next) return;
      rec = next;
    }
    if (rec.step === "summarize") await stepSummarize(ref, rec);
  } catch (err) {
    await handleFailure(ref, rec, task, err, lastAttempt);
  }
}

/**
 * Moves an `uploaded` recording to `processing`, or picks a `processing` one
 * back up. Null means this task has nothing to do: wrong run, or finished.
 *
 * RESUMES FROM WHAT EXISTS. A recording that is being retried may already
 * have its joined audio and its transcript; those steps are skipped.
 */
async function begin(ref: DocumentReference, run: number): Promise<Recording | null> {
  return getFirestore().runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const d = snap.data();
    if (!d || runOf(d) !== run) return null;
    if (d.status === "processing") return asRecording(ref.id, d);
    if (d.status !== "uploaded") return null;

    const step = d.transcription?.savedAt ? "summarize" : d.audio ? "transcribe" : "join";
    const now = Timestamp.now();
    tx.update(ref, {
      status: "processing",
      step,
      stepAt: now,
      processingAt: now,
      run,
    });
    return asRecording(ref.id, { ...d, status: "processing", step, stepAt: now });
  });
}

async function stepJoin(ref: DocumentReference, rec: Recording): Promise<Recording> {
  const run = runOf(rec);
  const { bytes } = await joinChunks(rec.id, rec.chunks ?? [], run);
  const audio = { path: paths.audio(rec.id), bytes };
  await guardedUpdate(ref, run, { step: "transcribe", stepAt: Timestamp.now(), audio }, "join");
  logger.info("Recording joined", { recordingId: rec.id, run, bytes, chunks: rec.chunks?.length ?? 0 });

  // Only once the joined file is written down. A failure here costs storage,
  // not audio; the sweep tries again.
  await deleteChunks(rec.id).catch((err) =>
    logger.warn("Chunks not deleted after joining", { recordingId: rec.id, error: describe(err) }));
  return { ...rec, step: "transcribe", audio };
}

/**
 * Sends the audio, or — if the transcript is already in — stores it.
 * Returns null while waiting for ElevenLabs.
 */
async function stepTranscribe(ref: DocumentReference, rec: Recording): Promise<Recording | null> {
  const run = runOf(rec);
  const t = rec.transcription ?? {};

  if (t.savedAt) return { ...rec, step: "summarize" };

  if (t.receivedAt) {
    const transcript = normalizeTranscript(await readJson<unknown>(paths.rawTranscript(rec.id)));
    await saveJson(paths.transcript(rec.id), transcript);
    const now = Timestamp.now();
    const stats = {
      "transcription.savedAt": now,
      "transcription.language": transcript.language,
      "transcription.audioSecs": transcript.audioSecs,
      "transcription.speakers": transcript.speakers.length,
      "transcription.words": transcript.words,
      "transcription.turns": transcript.turns.length,
      // Seconds spoken per speaker, for the talk time chart.
      talkTime: talkTimeOf(transcript),
    };

    /*
     * NOTHING WAS SAID. A recording of an empty room is not a failure — the
     * phone did its job — and there is nothing to summarise, so it is ready
     * as it is and says why.
     */
    if (transcript.words === 0) {
      await guardedUpdate(ref, run, {
        ...stats,
        status: "ready",
        step: null,
        stepAt: now,
        readyAt: now,
        noSpeech: true,
      }, "transcribe");
      logger.info("Recording has no speech", { recordingId: rec.id, run });
      return null;
    }

    await guardedUpdate(ref, run, { ...stats, step: "summarize", stepAt: now }, "transcribe");
    logger.info("Transcript stored", { recordingId: rec.id, run, words: transcript.words, speakers: transcript.speakers.length });
    return {
      ...rec,
      step: "summarize",
      transcription: { ...t, savedAt: now },
    };
  }

  // Sent already; the poll (or the sweep) brings it back.
  if (t.requestedAt) return null;

  const apiKey = ELEVENLABS_API_KEY.value();
  if (!keyIsSet(apiKey)) {
    throw new PermanentError("transcription.noKey", "ELEVENLABS_API_KEY is not set.");
  }
  if (!(await claimTranscription(ref, run))) return null;

  let sent: TranscriptionRequest;
  let bytes = 0;
  try {
    const [audio] = await getStorage().bucket().file(paths.audio(rec.id)).download();
    sent = await requestTranscription({ apiKey, audio, recordingId: rec.id, run });
    bytes = audio.length;
  } catch (err) {
    // Nothing reached ElevenLabs, or it refused: the next attempt may send at
    // once rather than wait out the claim.
    await quietly(() => guardedUpdate(ref, run, { "transcription.claimedAt": FieldValue.delete() }, "transcribe"),
      { recordingId: rec.id });
    throw err;
  }

  /*
   * FROM HERE A TRANSCRIPT IS ON ITS WAY, and has been paid for. If writing
   * that down fails, the claim is deliberately left in place: a retry within
   * its fifteen minutes finds it and does not send the audio a second time.
   */
  await guardedUpdate(ref, run, {
    "transcription.provider": "elevenlabs",
    "transcription.model": TRANSCRIBE_MODEL,
    "transcription.requestId": sent.requestId,
    "transcription.transcriptionId": sent.transcriptionId,
    "transcription.requestedAt": Timestamp.now(),
    "transcription.claimedAt": FieldValue.delete(),
  }, "transcribe");
  logger.info("Sent for transcription", {
    recordingId: rec.id, run, bytes, requestId: sent.requestId, transcriptionId: sent.transcriptionId,
  });

  // Finished already, and in the reply: nothing to wait for.
  if (sent.transcript) {
    await acceptTranscript(rec.id, run, sent.transcript, "response");
  } else {
    await enqueue({ kind: "poll", recordingId: rec.id, run, round: 1 }, "poll", pollDelay(1)).catch((err) =>
      logger.warn("Transcript poll not queued; the sweep will poll", { recordingId: rec.id, error: describe(err) }));
  }
  return null;
}

/**
 * Takes the right to send this run's audio. Two tasks for one recording —
 * possible, if a nudge from the sweep overlaps a slow task — must not both pay
 * for a transcription.
 */
async function claimTranscription(ref: DocumentReference, run: number): Promise<boolean> {
  return getFirestore().runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const d = snap.data();
    if (!d || runOf(d) !== run || d.status !== "processing" || d.step !== "transcribe") return false;
    const t = d.transcription ?? {};
    if (t.requestedAt || t.receivedAt) return false;
    const claimed = ms(t.claimedAt);
    if (claimed !== null && Date.now() - claimed < CLAIM_TTL_MS) return false;
    tx.update(ref, { "transcription.claimedAt": Timestamp.now() });
    return true;
  });
}

/** Writes the summary now, straight from the model. */
async function stepSummarize(ref: DocumentReference, rec: Recording): Promise<void> {
  const run = runOf(rec);
  let job = rec.summaryJob ?? undefined;

  if (!job) {
    const settings = await readSettings();
    const model = resolveModel(settings.summaryModel);
    job = {
      summaryId: ref.collection("summaries").doc().id,
      provider: model.provider,
      model: model.id,
      language: settings.language,
      state: "running",
      attempts: 0,
      queuedAt: Timestamp.now(),
    };
    await guardedUpdate(ref, run, { summaryJob: job, stepAt: Timestamp.now() }, "summarize");
  } else if (job.state !== "running") {
    // Left waiting by the old batch path (queued, submitted): written directly like any other.
    job = { ...job, state: "running" };
    await guardedUpdate(ref, run, { "summaryJob.state": "running", stepAt: Timestamp.now() }, "summarize");
  }

  const model = resolveModel(job.model);
  const input = await promptInputFor(rec, job.language);
  const outcome = await runDirect(model, buildPrompt(input), input.transcript.audioSecs, job.maxTokens);
  if (outcome.kind === "done") {
    const analysis = withNoteType(outcome.analysis, input.noteType);
    await saveRunSummary(ref, run, job, { ...outcome, analysis });
    // The first summary is the primary: its action items become tasks. A failure here is caught up later.
    await syncRecordingTasks(rec.id, job.summaryId, analysis, { ...rec, status: "ready" }).catch((err) =>
      logger.warn("Tasks not synced; GET /api/tasks will", { recordingId: rec.id, error: describe(err) }));
    return;
  }
  if (outcome.kind === "failed") throw new PermanentError(outcome.code, outcome.message);

  /*
   * WORTH ASKING AGAIN, BUT NOT FIVE TIMES. Each try is a whole summary paid
   * for, so a summary gets three tries — with more room if the last answer
   * was cut off — and then fails loudly.
   */
  const attempts = (job.attempts ?? 0) + 1;
  if (attempts >= MAX_SUMMARY_ATTEMPTS) throw new PermanentError(outcome.code, outcome.message);
  await guardedUpdate(ref, run, {
    "summaryJob.attempts": attempts,
    "summaryJob.lastError": { code: outcome.code, message: outcome.message },
    ...(outcome.code === "summary.truncated" ? { "summaryJob.maxTokens": boostedMaxTokens(model) } : {}),
  }, "summarize");
  throw new Error(`${outcome.code}: ${outcome.message}`);
}

async function handleFailure(
  ref: DocumentReference,
  rec: Recording,
  task: PipelineTask,
  err: unknown,
  lastAttempt: boolean,
): Promise<void> {
  const run = runOf(rec);
  if (err instanceof StaleError) return;

  if (err instanceof BusyError) {
    const waits = (rec.waits ?? 0) + 1;
    if (waits > MAX_WAITS) {
      await failRun(ref, run, rec.step, err.code, `${err.message} Still busy after three hours.`);
      return;
    }
    await quietly(() => guardedUpdate(ref, run, { waits }), { recordingId: rec.id });
    await enqueue(task, `wait-${waits}`, err.delaySeconds);
    logger.warn("Provider busy; trying again later", { recordingId: rec.id, run, step: rec.step, waits });
    return;
  }

  if (err instanceof PermanentError) {
    await failRun(ref, run, rec.step, err.code, err.message);
    return;
  }

  if (lastAttempt) {
    await failRun(ref, run, rec.step, `${rec.step ?? "processing"}.gaveUp`, describe(err));
    return;
  }
  logger.warn("Recording step failed; Cloud Tasks will retry", { recordingId: rec.id, run, step: rec.step, error: describe(err) });
  throw err;
}

/* -------------------------------------------------------------------- poll */

/**
 * About two hours of polling. After that the sweep asks every ten minutes, and
 * fails a transcript that has not come six hours after the audio was sent.
 */
const MAX_POLLS = 143;

/**
 * Seconds before poll `round`: after 5 s, then every 5 s for the first minute
 * (rounds 1 to 12), every 15 s up to five minutes (13 to 28), then every 60 s.
 */
function pollDelay(round: number): number {
  return round <= 12 ? 5 : round <= 28 ? 15 : 60;
}

/**
 * Asks ElevenLabs whether this run's transcript is done. A finished one goes to the receive path, which queues
 * the pipeline; otherwise the next poll is queued. Never throws: the sweep is the backstop.
 */
async function poll(task: PollTask): Promise<void> {
  const ref = recordingRef(task.recordingId);
  const d = (await ref.get()).data();
  const run = Number(task.run) || 1;
  if (!d || runOf(d) !== run || d.status !== "processing" || d.step !== "transcribe") return;
  const t = d.transcription ?? {};
  const id = typeof t.transcriptionId === "string" ? t.transcriptionId : null;
  if (t.receivedAt || !id) return;

  const apiKey = ELEVENLABS_API_KEY.value();
  if (keyIsSet(apiKey)) {
    try {
      // Noted, so the sweep does not ask as well while this is polling.
      await quietly(() => guardedUpdate(ref, run, { "transcription.polledAt": Timestamp.now() }, "transcribe"),
        { recordingId: ref.id });
      const transcript = await fetchTranscript(apiKey, id);
      if (transcript) {
        const outcome = await acceptTranscript(ref.id, run, transcript, "poll");
        logger.info("Transcript fetched", { recordingId: ref.id, run, round: task.round, outcome });
        return;
      }
    } catch (err) {
      logger.warn("Transcript poll failed; will ask again", { recordingId: ref.id, run, error: describe(err) });
    }
  }

  const round = (Number(task.round) || 1) + 1;
  if (round > MAX_POLLS) return;
  await enqueue({ kind: "poll", recordingId: ref.id, run, round }, "poll", pollDelay(round)).catch((err) =>
    logger.warn("Next transcript poll not queued; the sweep will poll", { recordingId: ref.id, error: describe(err) }));
}

/* ------------------------------------------------------------------- purge */

/**
 * A deleted recording's folder, swept again some minutes later.
 *
 * Deletion removes everything at once, but work already under way when the
 * admin pressed Delete — a join, a transcript arriving, a phone finishing an
 * upload through a link it was given earlier — can write a file a moment
 * after. The document says `deleted`, so nothing new is started; this removes
 * whatever such late work left behind.
 */
async function purge(task: PurgeTask): Promise<void> {
  const ref = recordingRef(task.recordingId);
  const d = (await ref.get()).data();
  if (d?.status !== "deleted") return;
  await wipeRecordingContent(ref);
  logger.info("Deleted recording swept again", { recordingId: task.recordingId, round: task.round });
}

/* ------------------------------------------------------------- resummarize */

/**
 * An admin's "summarise again with this model", to compare before switching.
 *
 * Written straight away — somebody is looking at the page waiting for it.
 * Stored beside the recording's other summaries; it becomes the one the list
 * shows only if the admin chooses it.
 */
async function resummarize(task: ResummarizeTask, lastAttempt: boolean): Promise<void> {
  const ref = recordingRef(task.recordingId);
  const snap = await ref.get();
  const d = snap.data();
  const job = d?.resummarize as
    | {
        summaryId: string; model: string; language: "en"; state: string; noteType?: string | null;
        requestedBy?: string; requestedByName?: string; attempts?: number; maxTokens?: number;
      }
    | undefined;
  if (!d || !job || job.summaryId !== task.summaryId || job.state !== "running" || d.status !== "ready") return;

  const finish = (patch: Record<string, unknown>) =>
    getFirestore().runTransaction(async (tx) => {
      const now = (await tx.get(ref)).data();
      if (now?.resummarize?.summaryId !== task.summaryId || now?.status !== "ready") return;
      tx.update(ref, patch);
    });
  const fail = (code: string, message: string) =>
    finish({
      "resummarize.state": "failed",
      "resummarize.error": { code, message: message.slice(0, 500) },
      "resummarize.finishedAt": Timestamp.now(),
    });

  const model = modelById(job.model);
  if (!model) {
    await fail("summary.model", `${job.model} is no longer an allowed model.`);
    return;
  }

  try {
    const rec = asRecording(ref.id, d);
    // A type asked for wins over the phone's hint; with neither, the model picks.
    const asked = noteTypeOf(job.noteType);
    const input = { ...(await promptInputFor(rec, job.language)), ...(asked ? { noteType: asked } : {}) };
    const outcome = await runDirect(model, buildPrompt(input), input.transcript.audioSecs, job.maxTokens);

    if (outcome.kind === "done") {
      const analysis = withNoteType(outcome.analysis, input.noteType);
      const madePrimary = await getFirestore().runTransaction(async (tx) => {
        const now = (await tx.get(ref)).data();
        if (now?.resummarize?.summaryId !== task.summaryId || now?.status !== "ready") return false;
        tx.set(ref.collection("summaries").doc(task.summaryId), {
          provider: model.provider,
          model: model.id,
          mode: "direct",
          language: job.language,
          run: runOf(now),
          requestedBy: job.requestedBy ?? null,
          requestedByName: job.requestedByName ?? null,
          createdAt: Timestamp.now(),
          analysis,
          usage: outcome.usage,
          stopReason: outcome.stopReason,
          costUsd: estimateCostUsd(model, outcome.usage),
        });
        // Re-run as a chosen type: it becomes the primary. A model-only re-run stays a comparison.
        tx.update(ref, {
          "resummarize.state": "done",
          "resummarize.finishedAt": Timestamp.now(),
          ...(asked
            ? {
                ...listFieldsOf(analysis, task.summaryId, model.id),
                primaryChosenBy: job.requestedBy ?? null,
                primaryChosenByName: job.requestedByName ?? null,
                primaryChosenAt: Timestamp.now(),
              }
            : {}),
        });
        return Boolean(asked);
      });
      if (madePrimary) await syncFromPrimary(ref.id);
      logger.info("Recording summarised again", { recordingId: ref.id, model: model.id, madePrimary, ...outcome.usage });
      return;
    }
    // The same three paid tries as any summary, with more room after a cut-off.
    const attempts = (job.attempts ?? 0) + 1;
    if (outcome.kind === "failed" || lastAttempt || attempts >= MAX_SUMMARY_ATTEMPTS) {
      await fail(outcome.code, outcome.message);
      return;
    }
    await finish({
      "resummarize.attempts": attempts,
      ...(outcome.code === "summary.truncated" ? { "resummarize.maxTokens": boostedMaxTokens(model) } : {}),
    });
    throw new Error(`${outcome.code}: ${outcome.message}`);
  } catch (err) {
    if (err instanceof PermanentError || lastAttempt) {
      await fail(err instanceof PermanentError ? err.code : "summary.gaveUp", describe(err));
      return;
    }
    throw err;
  }
}
