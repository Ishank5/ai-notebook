import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import * as logger from "firebase-functions/logger";

import { CHUNK_NAME, HttpsError, RECORDINGS, UUID, describe, enqueue, paths, type Caller } from "./recording/common.js";

/**
 * The phone's half of the recorder: upload links in, verified recordings out, and the status of its recordings.
 *
 * THE PHONE NEVER WRITES A RECORDING'S STATUS. It asks for a signed link per chunk, uploads to Storage directly,
 * and asks this file to check the result. Who recorded it and whether every chunk really arrived is decided here,
 * from the caller's login and from what is in the bucket.
 *
 * Signing needs one IAM grant: the function's service account must have `roles/iam.serviceAccountTokenCreator`
 * on itself (a Cloud Function has no private key and signs through `signBlob`).
 */

/** Sixty seconds at 32 kbps is ~240 KB; a megabyte leaves room for 48 kbps. */
const CHUNK_MAX_BYTES = 1_000_000;

/**
 * A sanity ceiling, not a length rule: chunks close early on every pause, so a long meeting can have hundreds.
 * 1,000, matching the phones (chunk numbers 0 to 999).
 */
const MAX_CHUNKS = 1000;
const MAX_TOTAL_BYTES = 100_000_000;

/** Enough for a phone that was offline all meeting to catch up in a few calls. */
const MAX_URLS_PER_CALL = 20;

/** Fifteen minutes: enough for a chunk on a slow connection, short enough that a copied link is useless. */
const URL_TTL_MS = 15 * 60 * 1000;

const MAX_GAPS = 200;
const MAX_DETAIL = 500;
const DAY_MS = 24 * 60 * 60 * 1000;

/** An MD5 digest, base64: 16 bytes is always 22 characters and `==`. */
const MD5_BASE64 = /^[A-Za-z0-9+/]{22}==$/;

/** Statuses after which no new audio may arrive. */
const CLOSED = new Set(["uploaded", "processing", "ready", "failed", "deleted"]);

/**
 * The phone names whose recording this is, and it must be the caller: a shared phone that switched accounts must
 * not create somebody's meeting under somebody else's name.
 */
function assertOwner(raw: unknown, caller: Caller): void {
  if (raw !== caller.uid) throw new HttpsError("permission-denied", "recorder.err.accountChanged");
}

function recordingIdOf(raw: unknown): string {
  if (typeof raw !== "string" || !UUID.test(raw)) throw new HttpsError("invalid-argument", "recorder.err.badRequest");
  return raw.toLowerCase();
}

function intIn(raw: unknown, min: number, max: number): number | null {
  return typeof raw === "number" && Number.isInteger(raw) && raw >= min && raw <= max ? raw : null;
}

function text(raw: unknown, max: number): string | null {
  if (typeof raw !== "string") return null;
  const trimmed = raw.trim();
  return trimmed ? trimmed.slice(0, max) : null;
}

/** Device-reported epoch milliseconds, kept only if plausible (2024 to 2100). */
function epochMs(raw: unknown): number | null {
  return intIn(raw, 1_704_067_200_000, 4_102_444_800_000);
}

/** When the meeting is filed: the phone's start time, unless its clock put it in the future. */
function meetingAtOf(startedAt: number | null): number {
  const now = Date.now();
  return startedAt !== null && startedAt <= now + DAY_MS ? startedAt : now;
}

/** The length the recorder planned, if the phone sent one (`plannedMs` or `planMs`). */
function plannedMsOf(data: Record<string, unknown>): number | null {
  return intIn(data.plannedMs ?? data.planMs, 1, DAY_MS);
}

type ChunkRequest = { seq: number; bytes: number; md5: string };

function chunksOf(raw: unknown): ChunkRequest[] {
  if (!Array.isArray(raw) || raw.length === 0 || raw.length > MAX_URLS_PER_CALL) {
    throw new HttpsError("invalid-argument", "recorder.err.badRequest");
  }
  const seen = new Set<number>();
  return raw.map((item) => {
    const c = (item ?? {}) as Record<string, unknown>;
    const seq = intIn(c.seq, 0, MAX_CHUNKS - 1);
    const bytes = intIn(c.bytes, 1, CHUNK_MAX_BYTES);
    const md5 = typeof c.md5 === "string" && MD5_BASE64.test(c.md5) ? c.md5 : null;
    if (seq === null || bytes === null || md5 === null || seen.has(seq)) {
      throw new HttpsError("invalid-argument", "recorder.err.badRequest");
    }
    seen.add(seq);
    return { seq, bytes, md5 };
  });
}

/**
 * `POST /api/recordings/upload-urls`: signed upload links for up to 20 chunks of one recording.
 *
 * THE FIRST CALL CREATES THE RECORDING, by the UUID the phone made when Record was pressed. A recording that
 * already exists must belong to the caller. Each link is bound to its chunk's MD5, so Storage refuses a chunk
 * corrupted in flight. Company fields from older phones are ignored.
 */
export async function recordingUploadUrls(caller: Caller, data: Record<string, unknown>) {
  assertOwner(data.ownerUid, caller);
  const recordingId = recordingIdOf(data.recordingId);
  const chunks = chunksOf(data.chunks);
  const ref = getFirestore().collection(RECORDINGS).doc(recordingId);

  try {
    await ref.create({
      ownerUid: caller.uid,
      ownerName: caller.name.slice(0, 120),
      status: "uploading",
      startedAt: epochMs(data.startedAt),
      meetingAt: meetingAtOf(epochMs(data.startedAt)),
      plannedMs: plannedMsOf(data),
      platform: text(data.platform, 20),
      appVersion: text(data.appVersion, 40),
      deviceModel: text(data.deviceModel, 80),
      createdAt: FieldValue.serverTimestamp(),
    });
    logger.info("Recording created", { recordingId, uid: caller.uid });
  } catch (err) {
    // ALREADY_EXISTS is the ordinary case for every call after the first.
    if ((err as { code?: number }).code !== 6) throw err;
    const existing = (await ref.get()).data() ?? {};
    if (existing.ownerUid !== caller.uid) {
      logger.warn("Upload refused: recording belongs to someone else", { recordingId, uid: caller.uid });
      throw new HttpsError("permission-denied", "recorder.err.notYours");
    }
    if (CLOSED.has(String(existing.status))) {
      throw new HttpsError("failed-precondition", "recorder.err.alreadyFinished");
    }
  }

  const bucket = getStorage().bucket();
  const expires = Date.now() + URL_TTL_MS;
  const lengthRange = `0,${CHUNK_MAX_BYTES}`;
  try {
    const urls = await Promise.all(
      chunks.map(async ({ seq, md5 }) => {
        const [url] = await bucket.file(paths.chunk(recordingId, seq)).getSignedUrl({
          version: "v4",
          action: "write",
          expires,
          contentType: "audio/aac",
          contentMd5: md5,
          extensionHeaders: { "x-goog-content-length-range": lengthRange },
        });
        // Every signed header must be sent exactly, or Storage answers 403. The phone sends these verbatim.
        return {
          seq,
          url,
          headers: { "Content-Type": "audio/aac", "Content-MD5": md5, "x-goog-content-length-range": lengthRange },
        };
      }),
    );
    return { urls };
  } catch (err) {
    // Almost always the missing Token Creator grant (see the top of this file).
    logger.error("Could not sign upload URLs", { recordingId, error: describe(err) });
    throw new HttpsError("internal", "recorder.err.server");
  }
}

/** The chunk numbers the phone has, sorted. Usually 0 to n-1, not always: a chunk lost on the phone is dropped. */
function seqsOf(raw: unknown): number[] {
  if (!Array.isArray(raw) || raw.length === 0 || raw.length > MAX_CHUNKS) {
    throw new HttpsError("invalid-argument", "recorder.err.badRequest");
  }
  const seqs = raw.map((v) => intIn(v, 0, MAX_CHUNKS - 1));
  if (seqs.some((s) => s === null)) throw new HttpsError("invalid-argument", "recorder.err.badRequest");
  const unique = [...new Set(seqs as number[])].sort((a, b) => a - b);
  if (unique.length !== seqs.length) throw new HttpsError("invalid-argument", "recorder.err.badRequest");
  return unique;
}

type Gap = { startMs: number; endMs: number | null; reason: string };

const GAP_REASONS = new Set(["call", "user", "interruption", "killed"]);
/** `planned`: it reached the planned length, nobody answered "Keep recording?", and it stopped by itself. */
const END_REASONS = new Set(["stopped", "interrupted", "limit", "planned"]);

function gapsOf(raw: unknown): Gap[] {
  if (raw === undefined || raw === null) return [];
  if (!Array.isArray(raw) || raw.length > MAX_GAPS) throw new HttpsError("invalid-argument", "recorder.err.badRequest");
  return raw.map((item) => {
    const g = (item ?? {}) as Record<string, unknown>;
    // Wall-clock offsets from the start, so they may run past the audio's own duration.
    const startMs = intIn(g.startMs, 0, DAY_MS);
    const endMs = g.endMs === null || g.endMs === undefined ? null : intIn(g.endMs, 0, DAY_MS);
    const reason = typeof g.reason === "string" && GAP_REASONS.has(g.reason) ? g.reason : null;
    if (startMs === null || reason === null || (endMs !== null && endMs < startMs)) {
      throw new HttpsError("invalid-argument", "recorder.err.badRequest");
    }
    return { startMs, endMs, reason };
  });
}

/**
 * `POST /api/recordings/finalize`: "every chunk is in; check it and close the recording."
 *
 * THE BUCKET IS THE TRUTH. Anything missing goes back to the phone by number (`{ ok: false, missing }`); only a
 * complete recording becomes `uploaded` and is queued for processing. IDEMPOTENT: a second call on a closed
 * recording only updates its title and details.
 */
export async function finalizeRecording(caller: Caller, data: Record<string, unknown>) {
  assertOwner(data.ownerUid, caller);
  const recordingId = recordingIdOf(data.recordingId);

  const seqs = seqsOf(data.seqs);
  const durationMs = intIn(data.durationMs, 0, DAY_MS);
  const endReason = typeof data.endReason === "string" && END_REASONS.has(data.endReason) ? data.endReason : null;
  if (durationMs === null || endReason === null) throw new HttpsError("invalid-argument", "recorder.err.badRequest");
  const gaps = gapsOf(data.gaps);

  const details = {
    title: text(data.title, 200),
    meetingType: text(data.meetingType, MAX_DETAIL),
    location: text(data.location, MAX_DETAIL),
    attendees: text(data.attendees, MAX_DETAIL),
  };

  const db = getFirestore();
  const ref = db.collection(RECORDINGS).doc(recordingId);
  const snap = await ref.get();
  // No chunk was ever asked for: the phone treats this as "upload first".
  if (!snap.exists) throw new HttpsError("not-found", "recorder.err.nothingUploaded");
  const existing = snap.data() ?? {};
  if (existing.ownerUid !== caller.uid) throw new HttpsError("permission-denied", "recorder.err.notYours");

  if (CLOSED.has(String(existing.status))) {
    // A deleted meeting's details were deleted on purpose; they are not written back.
    if (existing.status !== "deleted") {
      await ref.update({ ...details, detailsUpdatedAt: FieldValue.serverTimestamp() });
    }
    return { ok: true, missing: [] };
  }

  const [files] = await getStorage().bucket().getFiles({ prefix: paths.chunks(recordingId) });
  const present = new Map<number, number>();
  for (const file of files) {
    const match = CHUNK_NAME.exec(file.name);
    if (match) present.set(Number(match[1]), Number(file.metadata.size ?? 0));
  }

  const missing: number[] = [];
  let bytes = 0;
  for (const seq of seqs) {
    const size = present.get(seq);
    if (size === undefined) missing.push(seq);
    else bytes += size;
  }
  if (missing.length > 0) {
    logger.info("Finalize: chunks still missing", { recordingId, missing: missing.length });
    return { ok: false, missing };
  }
  if (bytes > MAX_TOTAL_BYTES) {
    logger.error("Recording over the size ceiling", { recordingId, bytes });
    throw new HttpsError("failed-precondition", "recorder.err.tooLarge");
  }

  // Closed in a transaction and only from `uploading`, so an overlapping call or an admin's delete is not undone.
  const startedAt = epochMs(data.startedAt) ?? existing.startedAt ?? null;
  const plannedMs = plannedMsOf(data);
  const closed = await db.runTransaction(async (tx) => {
    const current = (await tx.get(ref)).data();
    if (current?.status !== "uploading") return false;
    tx.update(ref, {
      status: "uploaded",
      run: 1,
      chunkCount: seqs.length,
      chunks: seqs,
      bytes,
      durationMs,
      startedAt,
      meetingAt: startedAt !== null ? meetingAtOf(startedAt) : (current.meetingAt ?? Date.now()),
      endedAt: epochMs(data.endedAt),
      endReason,
      gaps,
      ...(plannedMs !== null ? { plannedMs } : {}),
      ...details,
      uploadedAt: FieldValue.serverTimestamp(),
    });
    return true;
  });
  if (!closed) return { ok: true, missing: [] };
  logger.info("Recording uploaded", { recordingId, chunkCount: seqs.length, bytes, durationMs });

  // If queueing fails the phone is not told: its part is done, and the sweep queues it.
  await enqueue({ recordingId, run: 1 }, "start").catch((err) =>
    logger.warn("Processing not queued; the sweep will", { recordingId, error: describe(err) }));
  return { ok: true, missing: [] };
}

/**
 * `POST /api/recordings/statuses`: `{ ids }` (up to 100) -> `{ statuses: { [id]: status } }`, for the phone's
 * list. Ids that are not the caller's, or not there, are left out.
 */
export async function recordingStatuses(caller: Caller, data: Record<string, unknown>) {
  const ids = data.ids;
  if (!Array.isArray(ids) || ids.length > 100 || ids.some((id) => typeof id !== "string" || !UUID.test(id))) {
    throw new HttpsError("invalid-argument", "recorder.err.badRequest");
  }
  if (ids.length === 0) return { statuses: {} };
  const db = getFirestore();
  const unique = [...new Set((ids as string[]).map((id) => id.toLowerCase()))];
  const snaps = await db.getAll(...unique.map((id) => db.collection(RECORDINGS).doc(id)));
  const statuses: Record<string, string> = {};
  for (const snap of snaps) {
    const d = snap.data();
    if (d && d.ownerUid === caller.uid && typeof d.status === "string") statuses[snap.id] = d.status;
  }
  return { statuses };
}
