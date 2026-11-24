import { Timestamp, getFirestore } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";

import { enqueue, paths, recordingRef } from "./common.js";
import { failRun, runOf, saveJson } from "./store.js";

/**
 * A transcript arriving: found by the poll (or the sweep), in the send's reply, or by webhook.
 *
 * STORE, NOTE, HAND ON. The raw answer goes to the bucket exactly as it came,
 * the document records that it arrived, and the task is queued to do the
 * rest. Nothing slow happens here, because a webhook's caller may be waiting,
 * and nothing is lost if this returns early: the sweep notices a transcript
 * that arrived but was never stored.
 *
 * ANY TRANSCRIPT FOR THE CURRENT RUN IS ACCEPTED, even one whose request id is
 * not the one on record. That happens when a request reached ElevenLabs but
 * its answer never reached us and the task sent the audio again; both
 * transcripts are of the same audio, and the first to arrive is kept.
 */
export async function acceptTranscript(
  recordingId: string,
  run: number,
  transcription: unknown,
  via: "webhook" | "poll" | "response",
): Promise<"accepted" | "stale" | "duplicate"> {
  const ref = recordingRef(recordingId);
  const current = (await ref.get()).data();
  if (!current || runOf(current) !== run || current.status !== "processing" || current.step !== "transcribe") {
    logger.info("Transcript for a run that has moved on", { recordingId, run, via });
    return "stale";
  }
  if (current.transcription?.receivedAt) return "duplicate";

  await saveJson(paths.rawTranscript(recordingId), transcription);

  const transcriptionId = (transcription as { transcription_id?: unknown }).transcription_id;
  const accepted = await getFirestore().runTransaction(async (tx) => {
    const d = (await tx.get(ref)).data();
    if (!d || runOf(d) !== run || d.status !== "processing" || d.step !== "transcribe" || d.transcription?.receivedAt) {
      return false;
    }
    tx.update(ref, {
      "transcription.receivedAt": Timestamp.now(),
      "transcription.receivedVia": via,
      ...(typeof transcriptionId === "string" ? { "transcription.transcriptionId": transcriptionId } : {}),
    });
    return true;
  });
  if (!accepted) return "duplicate";

  logger.info("Transcript received", { recordingId, run, via });
  await enqueue({ recordingId, run }, "transcribed");
  return "accepted";
}

/** ElevenLabs said it could not transcribe this recording. */
export async function transcriptionFailed(recordingId: string, run: number, message: string): Promise<void> {
  await failRun(recordingRef(recordingId), run, "transcribe", "transcription.failed", message);
}
