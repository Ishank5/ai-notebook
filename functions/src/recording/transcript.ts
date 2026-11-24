/**
 * ElevenLabs' answer, turned into what people read: one line per speaker turn.
 *
 * ElevenLabs returns a flat list of words, spaces and sounds, each with a start
 * and end time and a speaker id. That is the right shape for a machine and the
 * wrong one for a person checking a summary against the audio, so it is cut
 * into turns here: a new line whenever the speaker changes, the room goes quiet
 * for a few seconds, or one speaker has held the floor long enough that a
 * single line would be a wall of text.
 *
 * PURE, AND TESTED THAT WAY. Nothing here touches Firebase, so the rules for
 * cutting turns can be checked against a fixture in milliseconds.
 */

/** One entry of ElevenLabs' `words` list, as delivered. */
export type RawWord = {
  text?: unknown;
  type?: unknown;
  start?: unknown;
  end?: unknown;
  speaker_id?: unknown;
};

export type Turn = {
  /** Speaker label shown to people: S1, S2 … in order of first appearance. */
  s: string;
  /** Start and end, seconds from the beginning of the audio. */
  t0: number;
  t1: number;
  text: string;
};

export type Transcript = {
  version: 1;
  language: string | null;
  languageProbability: number | null;
  audioSecs: number | null;
  speakers: { label: string; rawId: string | null; words: number; seconds: number }[];
  turns: Turn[];
  words: number;
};

/** Quiet long enough that the next words start a new line, same speaker or not. */
const PAUSE_SECS = 2.5;
/** One speaker's line is broken at the next pause of a second once it is this long. */
const LONG_TURN_SECS = 45;
const SOFT_PAUSE_SECS = 1;

function num(v: unknown): number | null {
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

function str(v: unknown): string {
  return typeof v === "string" ? v : "";
}

/**
 * The transcription object from a webhook or from `GET /transcripts/{id}`.
 *
 * Multichannel answers carry `transcripts[]`; nothing here asks for them, but a
 * recording is still readable if one ever arrives that way.
 */
export function normalizeTranscript(raw: unknown): Transcript {
  let source = (raw ?? {}) as Record<string, unknown>;
  if (Array.isArray(source.transcripts) && source.transcripts.length > 0) {
    source = (source.transcripts[0] ?? {}) as Record<string, unknown>;
  }
  const words = Array.isArray(source.words) ? (source.words as RawWord[]) : [];

  const labels = new Map<string, string>();
  const labelOf = (rawId: string | null): string => {
    const key = rawId ?? "";
    let label = labels.get(key);
    if (!label) {
      label = rawId === null ? "?" : `S${labels.size + 1}`;
      labels.set(key, label);
    }
    return label;
  };

  const turns: Turn[] = [];
  const stats = new Map<string, { rawId: string | null; words: number; seconds: number }>();
  let current: Turn | null = null;
  let pendingSpace = false;
  let wordCount = 0;

  /*
   * Spaces arrive as entries of their own. An answer with none at all would
   * otherwise run every word together, so then every word gets one.
   */
  const spaced = words.some((w) => w.type === "spacing");

  for (const w of words) {
    const type = str(w.type);
    const text = str(w.text);

    if (type === "spacing") {
      pendingSpace = current !== null;
      continue;
    }
    if (type !== "word" && type !== "audio_event") continue;
    if (!text.trim()) continue;
    if (!spaced) pendingSpace = current !== null;

    const start = num(w.start);
    const end = num(w.end) ?? start;
    const rawId = typeof w.speaker_id === "string" && w.speaker_id ? w.speaker_id : null;

    /*
     * A SOUND BELONGS TO WHOEVER IS TALKING. Applause and laughter usually
     * arrive with no speaker; starting a line for them would break a sentence
     * in half, so they join the current turn unless there is none.
     */
    const prev: Turn | null = current;
    const speakerless = type === "audio_event" && rawId === null;
    const label: string = speakerless && prev ? prev.s : labelOf(rawId);

    const gap = prev && start !== null ? start - prev.t1 : 0;
    const long = prev !== null && start !== null && start - prev.t0 >= LONG_TURN_SECS;
    const newTurn =
      prev === null ||
      (!speakerless && label !== prev.s) ||
      gap >= PAUSE_SECS ||
      (long && gap >= SOFT_PAUSE_SECS);

    let turn: Turn;
    if (newTurn || prev === null) {
      const fallback = prev ? prev.t1 : 0;
      turn = { s: label, t0: round(start ?? fallback), t1: round(end ?? start ?? fallback), text: "" };
      turns.push(turn);
      current = turn;
      pendingSpace = false;
    } else {
      turn = prev;
    }

    const piece = type === "audio_event" && !/^[([]/.test(text.trim()) ? `(${text.trim()})` : text;
    turn.text += (pendingSpace && turn.text ? " " : "") + piece;
    pendingSpace = false;
    if (end !== null) turn.t1 = round(Math.max(turn.t1, end));

    if (type === "word") {
      wordCount++;
      const s = stats.get(label) ?? { rawId, words: 0, seconds: 0 };
      s.words++;
      if (start !== null && end !== null && end > start) s.seconds += end - start;
      stats.set(label, s);
    }
  }

  for (const t of turns) t.text = t.text.replace(/\s+/g, " ").trim();

  const audioSecs = num(source.audio_duration_secs) ??
    (turns.length > 0 ? turns[turns.length - 1].t1 : null);

  return {
    version: 1,
    language: typeof source.language_code === "string" ? source.language_code : null,
    languageProbability: num(source.language_probability),
    audioSecs: audioSecs === null ? null : round(audioSecs),
    speakers: [...stats.entries()]
      .map(([label, s]) => ({ label, rawId: s.rawId, words: s.words, seconds: Math.round(s.seconds) }))
      .sort((a, b) => a.label.localeCompare(b.label, "en", { numeric: true })),
    turns: turns.filter((t) => t.text.length > 0),
    words: wordCount,
  };
}

function round(secs: number): number {
  return Math.round(secs * 100) / 100;
}

/** 754 → "12:34", 3754 → "1:02:34". */
export function clock(secs: number): string {
  const whole = Math.max(0, Math.floor(secs));
  const h = Math.floor(whole / 3600);
  const m = Math.floor((whole % 3600) / 60);
  const s = whole % 60;
  const mm = h > 0 ? String(m).padStart(2, "0") : String(m);
  return `${h > 0 ? `${h}:` : ""}${mm}:${String(s).padStart(2, "0")}`;
}

/**
 * The transcript as the model reads it: `[754] S2: words`.
 *
 * SECONDS, NOT CLOCK TIME, in the brackets. The model is asked to point each
 * action item and issue at the moment it was said; copying a number it can
 * see is reliable, converting 12:34 to 754 in its head is not.
 */
export function transcriptForModel(t: Transcript): string {
  return t.turns.map((turn) => `[${Math.floor(turn.t0)}] ${turn.s}: ${turn.text}`).join("\n");
}
