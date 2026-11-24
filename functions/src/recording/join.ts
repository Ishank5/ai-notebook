import { getStorage } from "firebase-admin/storage";

import { PermanentError, paths } from "./common.js";

/**
 * The phone's one-minute chunks, made into one file — without downloading any.
 *
 * ADTS IS A STREAM OF SELF-DESCRIBING FRAMES, and each chunk ends on a frame
 * boundary (the phone cuts it that way; see `Adts.validPrefix`). Two ADTS files
 * laid end to end are therefore one valid ADTS file, and Cloud Storage's
 * compose operation does exactly that on the server, in seconds.
 *
 * Compose takes at most 32 objects, so longer recordings are joined in two
 * levels: groups of 32 into parts, then the parts into the whole. The phone
 * sends at most 1,000 chunks, which is 32 parts: two levels always suffice.
 *
 * The chunks joined are the ones finalize confirmed and wrote down
 * as `chunks`, in that order — not whatever happens to be in the folder.
 */

const COMPOSE_MAX = 32;

/** The Storage file type, as firebase-admin's own copy of the library defines it. */
type File = ReturnType<ReturnType<ReturnType<typeof getStorage>["bucket"]>["file"]>;

export async function joinChunks(recordingId: string, seqs: number[], run: number): Promise<{ bytes: number }> {
  if (seqs.length === 0) {
    throw new PermanentError("join.noChunks", "The recording has no chunks to join.");
  }
  const bucket = getStorage().bucket();
  const ordered = [...seqs].sort((a, b) => a - b);

  const [present] = await bucket.getFiles({ prefix: paths.chunks(recordingId) });
  const names = new Set(present.map((f) => f.name));
  const missing = ordered.filter((seq) => !names.has(paths.chunk(recordingId, seq)));
  if (missing.length > 0) {
    throw new PermanentError(
      "join.missingChunks",
      `${missing.length} of ${ordered.length} chunks are no longer in the bucket.`,
    );
  }

  const sources = ordered.map((seq) => bucket.file(paths.chunk(recordingId, seq)));
  const target = audioFile(recordingId);

  if (sources.length <= COMPOSE_MAX) {
    await compose(sources, target);
  } else {
    const parts: File[] = [];
    for (let i = 0; i < sources.length; i += COMPOSE_MAX) {
      const part = bucket.file(paths.part(recordingId, run, parts.length));
      part.metadata = { contentType: "audio/aac" };
      await compose(sources.slice(i, i + COMPOSE_MAX), part);
      parts.push(part);
    }
    if (parts.length > COMPOSE_MAX) {
      throw new PermanentError("join.tooMany", `${sources.length} chunks is more than two levels of joining can hold.`);
    }
    await compose(parts, target);
    await Promise.all(parts.map((p) => p.delete({ ignoreNotFound: true }).catch(() => undefined)));
  }

  const [meta] = await audioFile(recordingId).getMetadata();
  return { bytes: Number(meta.size ?? 0) };
}

/**
 * Cloud Storage's compose. The Storage EMULATOR does not implement it (501),
 * so there — and only there — the same bytes are joined in memory, which lets
 * the end-to-end test run the real pipeline around it. A deployed function
 * never takes that path: a 501 from real Cloud Storage is thrown like any
 * other error.
 */
async function compose(sources: File[], target: File): Promise<void> {
  try {
    await getStorage().bucket().combine(sources, target);
  } catch (err) {
    if (process.env.FUNCTIONS_EMULATOR !== "true" || (err as { code?: unknown }).code !== 501) throw err;
    const pieces = await Promise.all(sources.map(async (s) => (await s.download())[0]));
    await target.save(Buffer.concat(pieces), { contentType: "audio/aac", resumable: false });
  }
}

/**
 * The joined file, typed as AAC. Set here rather than left to the library,
 * which guesses a type from the extension and has more than one guess for
 * `.aac` — and a browser only plays what it is told is audio.
 */
function audioFile(recordingId: string): File {
  const file = getStorage().bucket().file(paths.audio(recordingId));
  file.metadata = { contentType: "audio/aac" };
  return file;
}

/** The chunks, once the joined file is safely recorded. Best effort; the sweep repeats it. */
export async function deleteChunks(recordingId: string): Promise<void> {
  await getStorage().bucket().deleteFiles({ prefix: paths.chunks(recordingId), force: true });
}
