import {
  FieldValue,
  Timestamp,
  getFirestore,
  type QueryDocumentSnapshot,
} from "firebase-admin/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import * as logger from "firebase-functions/logger";

import {
  ELEVENLABS_API_KEY,
  RECORDINGS,
  REGION,
  SWEEP_LEASE_PATH,
  StaleError,
  describe,
  enqueue,
  keyIsSet,
} from "./common.js";
import { fetchTranscript } from "./elevenlabs.js";
import { deleteChunks } from "./join.js";
import { acceptTranscript } from "./receive.js";
import { failRun, guardedUpdate, ms, quietly, runOf, type SummaryJob } from "./store.js";

/**
 * Every five minutes: the part of processing that waits.
 *
 * - Queues any uploaded recording no task has picked up — a finalize call
 *   that could not queue one, or recordings uploaded before this existed.
 * - Asks ElevenLabs for any transcript the poll has not fetched, and fails one
 *   that never comes.
 * - Nudges a step that stalled, and fails it after three nudges.
 * - Fails a summary that has not finished within four hours.
 *
 * ONE RUN AT A TIME. A lease document stops an overlap if a run is slow; the
 * scheduler's own retry is off, because the next run is five minutes away.
 */

const LEASE_PATH = SWEEP_LEASE_PATH;
const LEASE_MS = 9 * 60 * 1000;

/**
 * How many in-flight recordings one sweep looks at. Far above what 300
 * meetings a day can put in flight at once; a query of this size is a few
 * megabytes and needs no index beyond the automatic ones.
 */
const SCAN_LIMIT = 2000;

/** The poll task asks every 5 s to 60 s; the sweep asks only if nobody has for ten minutes. */
const POLL_AFTER_MS = 10 * 60 * 1000;
const POLL_EVERY_MS = 10 * 60 * 1000;
/** A transcript six hours late is not coming. */
const TRANSCRIPTION_DEADLINE_MS = 6 * 60 * 60 * 1000;

/** A step with no progress for this long, and no reason to wait, is nudged. */
const STALL_MS = 30 * 60 * 1000;
const MAX_NUDGES = 3;
/** A claim to send the audio that was never followed up — the task died — expires. */
const CLAIM_TTL_MS = 15 * 60 * 1000;
/**
 * A summary written by a task — the pipeline's, or an admin's re-run — is
 * retried by Cloud Tasks: five attempts of up to 30 minutes with backoff come
 * to under four hours. Still running after that, every attempt died without
 * being able to say so.
 */
const DIRECT_DEADLINE_MS = 4 * 60 * 60 * 1000;

export const ntRecordingSweep = onSchedule(
  {
    schedule: "every 5 minutes",
    region: REGION,
    timeoutSeconds: 540,
    memory: "1GiB",
    maxInstances: 1,
    retryCount: 0,
    secrets: [ELEVENLABS_API_KEY],
  },
  async () => {
    await sweep(Date.now());
  },
);

export async function sweep(now: number): Promise<void> {
  if (!(await takeLease(now))) {
    logger.info("Recording sweep skipped: the previous one is still running");
    return;
  }
  try {
    for (const [name, part] of [
      ["startUploaded", startUploaded],
      ["watchProcessing", watchProcessing],
      ["watchResummaries", watchResummaries],
    ] as const) {
      try {
        await part(now);
      } catch (err) {
        logger.error("Recording sweep step failed", { step: name, error: describe(err) });
      }
    }
  } finally {
    await getFirestore().doc(LEASE_PATH).set({ until: 0, releasedAt: FieldValue.serverTimestamp() }, { merge: true });
  }
}

async function takeLease(now: number): Promise<boolean> {
  const ref = getFirestore().doc(LEASE_PATH);
  return getFirestore().runTransaction(async (tx) => {
    const until = (await tx.get(ref)).get("until");
    if (typeof until === "number" && until > now) return false;
    tx.set(ref, { until: now + LEASE_MS, takenAt: FieldValue.serverTimestamp() });
    return true;
  });
}

const recordings = () => getFirestore().collection(RECORDINGS);

/* ------------------------------------------------------------ uploaded */

async function startUploaded(now: number): Promise<void> {
  const snap = await recordings().where("status", "==", "uploaded").limit(200).get();
  for (const doc of snap.docs) {
    /*
     * Uploaded before processing existed: no run number, and no `meetingAt`
     * for the portal's date filter. Both are filled in once.
     */
    const patch: Record<string, unknown> = {};
    if (typeof doc.get("run") !== "number") patch.run = 1;
    if (typeof doc.get("meetingAt") !== "number") {
      const startedAt = doc.get("startedAt");
      patch.meetingAt = typeof startedAt === "number" ? startedAt : ms(doc.get("createdAt")) ?? now;
    }
    if (Object.keys(patch).length > 0) await doc.ref.update(patch);

    await enqueue({ recordingId: doc.id, run: runOf({ ...doc.data(), ...patch }) }, "start").catch((err) =>
      logger.warn("Could not queue an uploaded recording", { recordingId: doc.id, error: describe(err) }));
  }
}

/* ------------------------------------------------------------ processing */

async function watchProcessing(now: number): Promise<void> {
  const snap = await recordings().where("status", "==", "processing").limit(SCAN_LIMIT).get();
  const elevenLabsKey = ELEVENLABS_API_KEY.value();

  for (const doc of snap.docs) {
    const d = doc.data();
    const run = runOf(d);
    const stepAt = ms(d.stepAt) ?? now;
    const stepAge = now - stepAt;
    try {
      if (d.step === "transcribe") {
        await watchTranscription(doc, run, stepAt, now, elevenLabsKey);
      } else if (d.step === "summarize") {
        await watchSummary(doc, run, stepAge);
      } else if (stepAge > STALL_MS) {
        await nudge(doc, run, d.step ?? "join");
      }
      // Chunks left behind by a join whose tidy-up failed.
      if (d.step !== "join" && d.audio && !d.chunksCleared) {
        await deleteChunks(doc.id);
        await quietly(() => guardedUpdate(doc.ref, run, { chunksCleared: true }), { recordingId: doc.id });
      }
    } catch (err) {
      if (!(err instanceof StaleError)) {
        logger.warn("Recording not checked", { recordingId: doc.id, error: describe(err) });
      }
    }
  }
}

async function watchTranscription(
  doc: QueryDocumentSnapshot,
  run: number,
  stepAt: number,
  now: number,
  apiKey: string,
): Promise<void> {
  const t = (doc.get("transcription") ?? {}) as Record<string, unknown>;
  const stepAge = now - stepAt;

  /*
   * Arrived but never stored: the task queued after it was lost.
   * Timed from the later of its arrival and the last nudge, so a nudged task
   * waiting its turn in a busy queue is given the same ten minutes again
   * before the next — three nudges in a quarter of an hour would fail a
   * recording that was only waiting.
   */
  const received = ms(t.receivedAt);
  if (received !== null) {
    if (!t.savedAt && now - Math.max(received, stepAt) > POLL_AFTER_MS) await nudge(doc, run, "transcribe");
    return;
  }

  const requested = ms(t.requestedAt);
  if (requested === null) {
    // Never sent: a task claimed it and died, or none was ever queued. A
    // fresh claim is a task uploading right now, and is left alone.
    const claimed = ms(t.claimedAt);
    const busy = claimed !== null && now - claimed < CLAIM_TTL_MS;
    if (!busy && stepAge > STALL_MS) await nudge(doc, run, "transcribe");
    return;
  }

  const age = now - requested;
  if (age > TRANSCRIPTION_DEADLINE_MS) {
    await failRun(doc.ref, run, "transcribe", "transcription.timeout",
      "No transcript came back from ElevenLabs within six hours. Retry sends the audio again.");
    return;
  }

  const id = typeof t.transcriptionId === "string" ? t.transcriptionId : null;
  const polled = ms(t.polledAt);
  if (!id || age < POLL_AFTER_MS || (polled !== null && now - polled < POLL_EVERY_MS) || !keyIsSet(apiKey)) return;

  await quietly(() => guardedUpdate(doc.ref, run, { "transcription.polledAt": Timestamp.fromMillis(now) }, "transcribe"),
    { recordingId: doc.id });
  const transcript = await fetchTranscript(apiKey, id);
  if (transcript) {
    const outcome = await acceptTranscript(doc.id, run, transcript, "poll");
    logger.info("Late transcript fetched", { recordingId: doc.id, run, outcome });
  }
}

async function watchSummary(doc: QueryDocumentSnapshot, run: number, stepAge: number): Promise<void> {
  const job = doc.get("summaryJob") as SummaryJob | undefined;

  /*
   * No job yet, or one left waiting by the old batch path ("queued",
   * "submitted"): no task is working on it, so a stall is nudged and the
   * pipeline writes the summary directly.
   */
  if (!job || job.state !== "running") {
    if (stepAge > STALL_MS) await nudge(doc, run, "summarize");
    return;
  }
  if (stepAge > DIRECT_DEADLINE_MS) {
    await failRun(doc.ref, run, "summarize", "summarize.stuck", "The summary did not finish within four hours.");
  }
}

/**
 * Queues the task again for a step that stalled. Three nudges and the run is
 * failed instead — something is wrong that another attempt will not fix, and
 * an admin should see it.
 */
async function nudge(doc: QueryDocumentSnapshot, run: number, step: string): Promise<void> {
  const nudges = (typeof doc.get("nudges") === "number" ? doc.get("nudges") : 0) + 1;
  if (nudges > MAX_NUDGES) {
    await failRun(doc.ref, run, step, `${step}.stuck`, `The ${step} step stopped making progress.`);
    return;
  }
  await guardedUpdate(doc.ref, run, { nudges, stepAt: Timestamp.now() });
  await enqueue({ recordingId: doc.id, run }, `nudge-${nudges}`);
  logger.warn("Stalled recording nudged", { recordingId: doc.id, run, step, nudges });
}

/* ----------------------------------------------------------- resummaries */

/** An admin's re-run whose task died is reported, not left spinning. */
async function watchResummaries(now: number): Promise<void> {
  const snap = await recordings().where("resummarize.state", "==", "running").limit(100).get();
  for (const doc of snap.docs) {
    const job = doc.get("resummarize") as { summaryId?: string; requestedAt?: unknown };
    const requested = ms(job.requestedAt);
    if (requested === null || now - requested < DIRECT_DEADLINE_MS) continue;
    // Only the re-run that is still the current one, and still running.
    await getFirestore().runTransaction(async (tx) => {
      const current = (await tx.get(doc.ref)).get("resummarize") as { summaryId?: string; state?: string } | undefined;
      if (current?.summaryId !== job.summaryId || current?.state !== "running") return;
      tx.update(doc.ref, {
        "resummarize.state": "failed",
        "resummarize.error": { code: "summary.stuck", message: "The summary did not finish within four hours." },
        "resummarize.finishedAt": Timestamp.fromMillis(now),
      });
    });
  }
}
