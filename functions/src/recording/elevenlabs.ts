import { createHmac, timingSafeEqual } from "node:crypto";

import { classifyHttp, describe } from "./common.js";

/**
 * ElevenLabs Scribe v2: the whole recording in, a diarised transcript out.
 *
 * ASYNCHRONOUS, POLLED. The request uploads the audio and returns at once with
 * a transcription id; the pipeline then polls `fetchTranscript` by that id
 * (pipeline.ts), with the sweep as the backstop. AI Notebook registers no
 * webhook of its own. ElevenLabs still posts the result to every webhook on
 * the shared account (another app's), so the metadata uses keys of our own
 * (`app`, `ntRecordingId`) that other apps on the account do not read, and ours
 * ignores any payload without `app: "ai-notetaker"`.
 *
 * NO LANGUAGE AND NO KEY TERMS: ElevenLabs detects the language, so a meeting
 * in any language is transcribed as spoken.
 *
 * THE AUDIO GOES IN THE REQUEST rather than as a link for ElevenLabs to fetch,
 * so no readable link to a meeting exists anywhere.
 */

export const APP_TAG = "ai-notetaker";

export const TRANSCRIBE_MODEL = "scribe_v2";

const BASE = "https://api.elevenlabs.io";

/**
 * The answer to a transcription request. `transcript` is set only if
 * ElevenLabs finished at once and put it in the reply; normally it is polled.
 */
export type TranscriptionRequest = {
  requestId: string | null;
  transcriptionId: string | null;
  transcript: unknown | null;
};

/**
 * Whether a body is a finished transcript: one with its list of words (or,
 * for multichannel audio, a list of transcripts that each have one). Anything
 * else — a status report, an error, a shape ElevenLabs has since changed — is
 * not taken for one, because a transcript accepted wrongly is a meeting marked
 * "no speech" and never summarised.
 */
export function isTranscript(value: unknown): boolean {
  if (!value || typeof value !== "object") return false;
  const v = value as { words?: unknown; transcripts?: unknown };
  if (Array.isArray(v.words)) return true;
  return Array.isArray(v.transcripts) && v.transcripts.length > 0 &&
    v.transcripts.every((t) => Array.isArray((t as { words?: unknown } | null)?.words));
}

/** Sends one recording for transcription. The metadata names the recording and run for a webhook. */
export async function requestTranscription(opts: {
  apiKey: string;
  audio: Buffer;
  recordingId: string;
  run: number;
}): Promise<TranscriptionRequest> {
  const form = new FormData();
  form.append("model_id", TRANSCRIBE_MODEL);
  // The Buffer as it is (a cast for the types only): copying would hold the meeting in memory twice.
  form.append("file", new Blob([opts.audio as unknown as Uint8Array<ArrayBuffer>], { type: "audio/aac" }), "audio.aac");
  // Who spoke when, word by word — the transcript view and the summary's
  // "raised by" both depend on it.
  form.append("diarize", "true");
  form.append("timestamps_granularity", "word");
  // "(applause)", "(laughter)": a room's reaction is part of how it went.
  form.append("tag_audio_events", "true");
  // Async: the request returns at once and the transcript is polled for.
  form.append("webhook", "true");
  form.append("webhook_metadata", JSON.stringify({ app: APP_TAG, ntRecordingId: opts.recordingId, run: opts.run }));

  let res: Response;
  try {
    res = await fetch(`${BASE}/v1/speech-to-text`, {
      method: "POST",
      headers: { "xi-api-key": opts.apiKey },
      body: form,
      signal: AbortSignal.timeout(5 * 60 * 1000),
    });
  } catch (err) {
    throw new Error(`ElevenLabs unreachable: ${describe(err)}`);
  }

  const body = await res.text();
  if (!res.ok) throw classifyHttp(res.status, "transcription", detailOf(body));

  let parsed: Record<string, unknown> = {};
  try {
    parsed = JSON.parse(body) as Record<string, unknown>;
  } catch {
    // Accepted without a readable body: nothing to poll by, so the sweep fails it in time.
  }
  return {
    requestId: typeof parsed.request_id === "string" ? parsed.request_id : null,
    transcriptionId: typeof parsed.transcription_id === "string" ? parsed.transcription_id : null,
    transcript: isTranscript(parsed) ? parsed : null,
  };
}

/**
 * A finished transcript by id, or null while it is still being made.
 *
 * 404 is "not ready", not "gone": the id was issued by the request above. A
 * 200 whose body is not a transcript is also "not ready".
 */
export async function fetchTranscript(apiKey: string, transcriptionId: string): Promise<unknown | null> {
  let res: Response;
  try {
    res = await fetch(
      `${BASE}/v1/speech-to-text/transcripts/${encodeURIComponent(transcriptionId)}`,
      { headers: { "xi-api-key": apiKey }, signal: AbortSignal.timeout(60 * 1000) },
    );
  } catch (err) {
    throw new Error(`ElevenLabs unreachable: ${describe(err)}`);
  }
  if (res.status === 404) return null;
  const body = await res.text();
  if (!res.ok) throw classifyHttp(res.status, "transcription", detailOf(body));
  let parsed: unknown;
  try {
    parsed = JSON.parse(body) as unknown;
  } catch {
    throw new Error("ElevenLabs returned a transcript that is not JSON");
  }
  return isTranscript(parsed) ? parsed : null;
}

/** The `detail` of an ElevenLabs error, which is where it says what was wrong. */
function detailOf(body: string): string {
  try {
    const parsed = JSON.parse(body) as { detail?: unknown };
    const detail = parsed.detail;
    if (typeof detail === "string") return detail.slice(0, 300);
    if (detail && typeof detail === "object") {
      const message = (detail as { message?: unknown }).message;
      if (typeof message === "string") return message.slice(0, 300);
      return JSON.stringify(detail).slice(0, 300);
    }
  } catch {
    // not JSON
  }
  return body.slice(0, 200);
}

/* --------------------------------------------------------------- webhook */

/** ElevenLabs' own replay window: a signature older than this is refused. */
const SIGNATURE_TOLERANCE_SECS = 30 * 60;
/** And one from the future only by the width of a clock drift. */
const FUTURE_TOLERANCE_SECS = 5 * 60;

/**
 * Checks the `ElevenLabs-Signature` header: `t=<unix seconds>,v0=<hex>`, where
 * the hex is HMAC-SHA256 of `"<t>.<raw body>"` keyed with the webhook's secret.
 *
 * MORE THAN ONE `v0` IS NORMAL while a secret is being rotated — ElevenLabs
 * signs with both — and any one matching is enough. Compared in constant time.
 * The raw bytes are what was signed; the body must not be parsed first.
 */
export function verifySignature(
  rawBody: Buffer,
  header: string | undefined,
  secret: string,
  nowMs: number,
): boolean {
  if (!header || !secret) return false;
  let timestamp: string | null = null;
  const signatures: string[] = [];
  for (const part of header.split(",")) {
    const eq = part.indexOf("=");
    if (eq <= 0) continue;
    const key = part.slice(0, eq).trim();
    const value = part.slice(eq + 1).trim();
    if (key === "t") timestamp = value;
    else if (key === "v0") signatures.push(value);
  }
  if (!timestamp || !/^\d{1,12}$/.test(timestamp) || signatures.length === 0) return false;

  const ageSecs = nowMs / 1000 - Number(timestamp);
  if (ageSecs > SIGNATURE_TOLERANCE_SECS || ageSecs < -FUTURE_TOLERANCE_SECS) return false;

  const expected = createHmac("sha256", secret)
    .update(`${timestamp}.`)
    .update(rawBody)
    .digest();
  return signatures.some((sig) => {
    if (!/^[0-9a-f]+$/i.test(sig) || sig.length !== expected.length * 2) return false;
    return timingSafeEqual(Buffer.from(sig, "hex"), expected);
  });
}

/** The body ElevenLabs posts, as far as this code relies on it. */
export type WebhookPayload = {
  type?: unknown;
  data?: {
    request_id?: unknown;
    webhook_metadata?: unknown;
    transcription?: unknown;
    error?: unknown;
  };
};

/**
 * Which of our recordings a webhook is about, or null for anybody else's (another app's
 * transcripts reach the same webhooks). Read as a JSON string or as an object.
 */
export function metadataOf(payload: WebhookPayload): { recordingId: string; run: number } | null {
  let meta = payload.data?.webhook_metadata;
  if (typeof meta === "string") {
    try {
      meta = JSON.parse(meta);
    } catch {
      return null;
    }
  }
  if (!meta || typeof meta !== "object") return null;
  const { app, ntRecordingId: recordingId, run } = meta as { app?: unknown; ntRecordingId?: unknown; run?: unknown };
  if (app !== APP_TAG) return null;
  if (typeof recordingId !== "string" || !/^[0-9a-f-]{36}$/.test(recordingId)) return null;
  const n = typeof run === "string" ? Number(run) : run;
  if (typeof n !== "number" || !Number.isInteger(n) || n < 1) return null;
  return { recordingId, run: n };
}

