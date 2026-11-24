/**
 * Small, pure helpers the Recordings screens share.
 */

/** 754 → "12:34", 3754 → "1:02:34". Audio positions, not clock times. */
export function clock(secs) {
  if (!Number.isFinite(secs) || secs < 0) return "";
  const whole = Math.floor(secs);
  const h = Math.floor(whole / 3600);
  const m = Math.floor((whole % 3600) / 60);
  const s = whole % 60;
  return `${h > 0 ? `${h}:` : ""}${h > 0 ? String(m).padStart(2, "0") : m}:${String(s).padStart(2, "0")}`;
}

/** Milliseconds as "1 h 23 min", "45 min" or "30 s", through the caller's `t`. */
export function duration(t, ms) {
  if (!Number.isFinite(ms) || ms <= 0) return "";
  const totalSecs = Math.round(ms / 1000);
  if (totalSecs < 60) return t("dur.seconds", { s: totalSecs });
  const totalMins = Math.round(totalSecs / 60);
  const h = Math.floor(totalMins / 60);
  const m = totalMins % 60;
  return h > 0 ? t("dur.hours", { h, m }) : t("dur.minutes", { m });
}

export const toMillis = (v) =>
  typeof v === "number" ? v : typeof v?.toMillis === "function" ? v.toMillis() : null;

/**
 * Where a recording is, as the list and the detail both show it.
 *
 * Four words for a person scanning a list (Uploading, Processing, Ready,
 * Failed), with the step underneath for whoever wants to know why a meeting
 * from this morning is not ready yet.
 */
export function statusOf(r) {
  switch (r.status) {
    case "uploading":
      return { key: "uploading", tone: "warning", dot: true };
    case "uploaded":
      return { key: "processing", tone: "primary", dot: true, detail: "queued" };
    case "processing": {
      const tx = r.transcription ?? {};
      let detail = "queued";
      if (r.step === "join") detail = "joining";
      else if (r.step === "transcribe") detail = tx.receivedAt ? "storing" : tx.requestedAt ? "transcribing" : "sending";
      else if (r.step === "summarize") detail = "summarizing";
      return { key: "processing", tone: "primary", dot: true, detail };
    }
    case "ready":
      return r.noSpeech ? { key: "noSpeech", tone: "neutral" } : { key: "ready", tone: "success" };
    case "failed":
      return { key: "failed", tone: "danger" };
    case "deleted":
      return { key: "deleted", tone: "neutral" };
    default:
      return { key: "unknown", tone: "neutral" };
  }
}

/** The name a recording goes by: what the recorder typed, else what the summary called it. */
export function titleOf(r) {
  return (r.title && r.title.trim()) || (r.summaryTitle && r.summaryTitle.trim()) || "";
}

/** "Harpreet Kaur (S1)" for a labelled speaker the summary identified, else "S1". */
export function speakerNames(analysis) {
  const names = new Map();
  for (const p of analysis?.participants ?? []) {
    for (const label of p.speakers ?? []) {
      if (p.name && !names.has(label)) names.set(label, p.name);
    }
  }
  return names;
}

/** The transcript as a plain text file, one line per turn. */
export function transcriptText(transcript, names, heading) {
  const lines = [heading, ""];
  for (const turn of transcript?.turns ?? []) {
    const who = names.get(turn.s) ? `${names.get(turn.s)} (${turn.s})` : turn.s;
    lines.push(`[${clock(turn.t0)}] ${who}: ${turn.text}`);
  }
  return lines.join("\n") + "\n";
}

/** Starts a download of text the page already has, without a round trip. */
export function saveText(fileName, text) {
  const url = URL.createObjectURL(new Blob([text], { type: "text/plain;charset=utf-8" }));
  const a = document.createElement("a");
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** A pipeline error code ("transcription.auth") as a sentence, or the code itself. */
export function errorLabel(t, code) {
  const text = t(`error.${String(code).replace(/\./g, "_")}`, { defaultValue: "" });
  return text || t("error.other", { code });
}
