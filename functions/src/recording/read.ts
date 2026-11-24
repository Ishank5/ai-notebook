import { Timestamp, getFirestore, type DocumentData } from "firebase-admin/firestore";

import { HttpsError, RECORDINGS, UUID, paths, recordingRef, type Caller } from "./common.js";
import { talkTimeOf, type TalkTime } from "./notetype.js";
import { readJson } from "./store.js";
import type { Transcript } from "./transcript.js";

/**
 * The list and detail reads, in the JSON shapes the Render API returned, so
 * the copied phone and admin screens parse them unchanged: a recording is its document with `id`, every time as
 * epoch milliseconds, and empty fields left out. Members see their own; admins see everyone's.
 */

/** A ceiling on rows, not a page size: past it the screen says so and asks for a narrower range. */
const MAX_ROWS = 1500;

/** Firestore Timestamps as epoch ms, all the way down. */
function plain(value: unknown): unknown {
  if (value instanceof Timestamp) return value.toMillis();
  if (Array.isArray(value)) return value.map(plain);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, plain(v)]));
  }
  return value;
}

/** A recording document as the API returns it. */
export function recordingOut(id: string, d: DocumentData): Record<string, unknown> {
  const out: Record<string, unknown> = { id };
  for (const [key, value] of Object.entries(d)) {
    if (value === null || value === undefined) continue;
    out[key] = plain(value);
  }
  return out;
}

/** `GET /api/recordings?from=&to=` (epoch ms): `{ rows, truncated }`, newest meeting first. */
export async function listRecordings(caller: Caller, query: Record<string, unknown>) {
  const from = Number(query.from);
  const to = Number(query.to);
  if (!Number.isFinite(from) || !Number.isFinite(to) || to < from) {
    throw new HttpsError("invalid-argument", "recordings.err.badRequest");
  }
  const recordings = getFirestore().collection(RECORDINGS);

  let rows: Record<string, unknown>[];
  if (caller.admin) {
    const snap = await recordings
      .where("meetingAt", ">=", from)
      .where("meetingAt", "<=", to)
      .orderBy("meetingAt", "desc")
      .limit(MAX_ROWS)
      .get();
    rows = snap.docs.map((doc) => recordingOut(doc.id, doc.data()));
  } else {
    // One person's recordings are few: filtered here, so no composite index is needed.
    const snap = await recordings.where("ownerUid", "==", caller.uid).get();
    rows = snap.docs
      .map((doc) => recordingOut(doc.id, doc.data()))
      .filter((r) => typeof r.meetingAt === "number" && r.meetingAt >= from && r.meetingAt <= to)
      .sort((a, b) => (b.meetingAt as number) - (a.meetingAt as number))
      .slice(0, MAX_ROWS);
  }
  return { rows, truncated: rows.length >= MAX_ROWS };
}

/**
 * `GET /api/recordings/:id`: `{ recording, summaries, access }`. Summaries newest first; the last 50 access log
 * entries for an admin only. Somebody else's recording is "not found".
 */
export async function recordingDetail(caller: Caller, rawId: unknown) {
  if (typeof rawId !== "string" || !UUID.test(rawId)) throw new HttpsError("not-found", "recordings.err.notFound");
  const ref = recordingRef(rawId.toLowerCase());
  const snap = await ref.get();
  const d = snap.data();
  if (!d || (!caller.admin && d.ownerUid !== caller.uid)) {
    throw new HttpsError("not-found", "recordings.err.notFound");
  }

  const summariesSnap = await ref.collection("summaries").orderBy("createdAt", "desc").get();
  const summaries = summariesSnap.docs.map((doc) => {
    const s = doc.data();
    return {
      id: doc.id,
      provider: s.provider ?? null,
      model: s.model ?? null,
      mode: s.mode ?? null,
      language: s.language ?? null,
      run: s.run ?? null,
      requestedBy: s.requestedBy ?? null,
      requestedByName: s.requestedByName ?? null,
      createdAt: s.createdAt instanceof Timestamp ? s.createdAt.toMillis() : null,
      analysis: s.analysis ?? null,
      usage: s.usage ?? null,
      stopReason: s.stopReason ?? null,
      costUsd: s.costUsd ?? null,
    };
  });

  let access: { id: string; uid: string; name: string; action: string; at: number | null }[] = [];
  if (caller.admin) {
    const accessSnap = await ref.collection("access").orderBy("at", "desc").limit(50).get();
    access = accessSnap.docs.map((doc) => {
      const a = doc.data();
      return {
        id: doc.id,
        uid: String(a.uid ?? ""),
        name: String(a.name ?? ""),
        action: String(a.action ?? ""),
        at: a.at instanceof Timestamp ? a.at.toMillis() : null,
      };
    });
  }

  // Talk time is saved when the transcript is stored; an older recording gets it worked out now (and saved).
  let talkTime: TalkTime = Array.isArray(d.talkTime) ? d.talkTime : [];
  if (!Array.isArray(d.talkTime) && d.transcription?.savedAt && d.status !== "deleted") {
    try {
      talkTime = talkTimeOf(await readJson<Transcript>(paths.transcript(ref.id)));
      await ref.update({ talkTime }).catch(() => undefined);
    } catch {
      talkTime = [];
    }
  }

  return { recording: { ...recordingOut(snap.id, d), talkTime }, summaries, access, talkTime };
}
