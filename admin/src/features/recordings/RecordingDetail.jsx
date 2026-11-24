import {
  ArrowLeft,
  BookOpenText,
  Check,
  Download,
  FileText,
  Layers,
  ListChecks,
  Mail,
  MessagesSquare,
  Network,
  Play,
  Printer,
  RotateCcw,
  Trash2,
  Trophy,
} from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { toast } from "sonner";

import { TypeBadge, noteTypeOf } from "../../components/NoteType";
import { Btn, Chip, Confirm, EmptyState, ErrorState, Skeleton, cn } from "../../components/ui";
import { useAuth } from "../../lib/auth";
import {
  deleteRecording,
  errorKey,
  media,
  processWhatArrived,
  retryRecording,
  setPrimarySummary,
  uploadProgress,
} from "./api";
import { EmailModal } from "./EmailModal";
import { useFormat } from "./fmt";
import { clock, duration, errorLabel, saveText, speakerNames, statusOf, titleOf, toMillis, transcriptText } from "./format";
import { MindMap } from "./MindMap";
import { TalkTime, Topics, TypeSections, talkTimeOf } from "./NoteParts";
import { Player } from "./Player";
import { PrintReport } from "./PrintReport";
import { Rerun } from "./Rerun";
import { t } from "./strings";
import { Flashcards, Quiz, StudyNotes } from "./Study";
import { SummaryView } from "./Summary";
import { Transcript } from "./Transcript";
import { useRecording } from "./useRecordings";

/** A fresh page per note and per `?t=`, so tabs and the player never carry over from another note. */
export default function RecordingDetail() {
  const { recordingId } = useParams();
  const [params] = useSearchParams();
  return <NotePage key={`${recordingId}|${params.get("t") ?? ""}`} recordingId={recordingId} startParam={params.get("t")} />;
}

/**
 * One note: the report and what the AI made of it in tabs (transcript, mind map, and for lectures
 * study notes, flashcards and a quiz), with the audio and talk time beside them. Every timestamp
 * plays the audio from there. `?t=<seconds>` opens the transcript at that moment with the player
 * cued there. Re-run, choosing the main summary, delete and the access log are the admin's.
 */
function NotePage({ recordingId, startParam }) {
  const { dateTime } = useFormat();
  const { can } = useAuth();
  const manages = can("recordings.manage");
  const seesLog = can("recordings.read_all");
  const { status, recording: r, summaries, access } = useRecording(recordingId);

  const startAt = startParam !== null && Number.isFinite(Number(startParam)) ? Math.max(0, Number(startParam)) : null;
  const player = useRef(null);
  const started = useRef(false);
  const [tab, setTab] = useState(startAt !== null ? "transcript" : "report");
  const [position, setPosition] = useState(startAt);
  // The transcript is kept with the version it was loaded for, so "Transcribe again" never leaves the old one up.
  const [loaded, setLoaded] = useState({ key: null, transcript: null });
  const [loadingTranscript, setLoadingTranscript] = useState(false);
  const [chosen, setChosen] = useState(null);
  const [busy, setBusy] = useState(null);
  const [emailOpen, setEmailOpen] = useState(false);

  const summary = useMemo(() => {
    if (summaries.length === 0) return null;
    return summaries.find((s) => s.id === (chosen ?? r?.summaryId)) ?? summaries[0];
  }, [summaries, chosen, r?.summaryId]);
  const analysis = summary?.analysis ?? null;
  const names = useMemo(() => speakerNames(analysis), [analysis]);

  const seek = (seconds) => player.current?.seek(seconds);

  async function run(label, work, success) {
    setBusy(label);
    try {
      const result = await work();
      if (success) toast.success(success);
      return result ?? true;
    } catch (err) {
      toast.error(t(errorKey(err)));
      return null;
    } finally {
      setBusy(null);
    }
  }

  const transcriptKey = toMillis(r?.transcription?.savedAt);
  const transcript = loaded.key !== null && loaded.key === transcriptKey ? loaded.transcript : null;
  const hasAudio = Boolean(r?.audio?.path) && r?.status !== "deleted";
  const hasTranscript = Boolean(r?.transcription?.savedAt) && r?.status !== "deleted";

  const loadTranscript = () =>
    run("transcript", async () => {
      const key = transcriptKey;
      setLoadingTranscript(true);
      try {
        const { transcript: tr } = await media(recordingId, "transcript");
        setLoaded({ key, transcript: tr });
      } finally {
        setLoadingTranscript(false);
      }
    });

  // Opened at a moment (from a task or an Ask AI citation): show that line and cue the audio there.
  useEffect(() => {
    if (startAt === null || started.current || status !== "ready" || !r) return undefined;
    const id = setTimeout(() => {
      started.current = true;
      if (hasTranscript) loadTranscript();
      if (hasAudio) player.current?.seek(startAt);
    }, 0);
    return () => clearTimeout(id);
    // Runs once, when the note first arrives.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, r?.id]);

  const downloadTranscript = () =>
    run("download-transcript", async () => {
      const { transcript: tr } = await media(recordingId, "download-transcript");
      const heading = `${titleOf(r) || t("untitled")} · ${r.ownerName ?? ""} · ${r.meetingAt ? dateTime(r.meetingAt) : ""}`;
      saveText(`${fileBase(r)}.txt`, transcriptText(tr, names, heading));
    });

  // A plain link to the signed URL: the URL says "attachment", so the browser saves it. No fetch (the bucket has no CORS for this site).
  const downloadAudio = () =>
    run("download-audio", async () => {
      const { url } = await media(recordingId, "download-audio");
      const a = document.createElement("a");
      a.href = url;
      a.rel = "noopener";
      document.body.appendChild(a);
      a.click();
      a.remove();
    });

  if (status === "loading") return <Frame><DetailSkeleton /></Frame>;
  if (status === "error") return <Frame><ErrorState title={t("detail.errorTitle")} body={t("errorBody")} /></Frame>;
  if (status === "missing" || !r) return <Frame><EmptyState title={t("detail.missingTitle")} hint={t("detail.missingBody")} /></Frame>;

  const st = statusOf(r);
  const title = titleOf(r) || analysis?.title || t("untitled");
  const type = noteTypeOf(r, analysis);
  const meta = [r.ownerName, r.meetingAt ? dateTime(r.meetingAt) : null, duration(t, r.durationMs)].filter(Boolean);
  const canResummarize = manages && r.status === "ready" && hasTranscript && !r.noSpeech;
  const ready = r.status === "ready" && Boolean(analysis);
  const talkTime = talkTimeOf(r, transcript);
  const isLecture = type === "lecture" || ["study_notes", "flashcards", "quiz"].some((k) => analysis?.[k]?.length);

  const tabs = [
    { id: "report", label: "Report", icon: FileText },
    { id: "transcript", label: "Transcript", icon: ListChecks, show: hasTranscript || r.status !== "deleted" },
    { id: "mindmap", label: "Mind map", icon: Network, show: Boolean(analysis) },
    { id: "study", label: "Study notes", icon: BookOpenText, show: Boolean(analysis) && isLecture },
    { id: "cards", label: "Flashcards", icon: Layers, show: Boolean(analysis) && isLecture },
    { id: "quiz", label: "Quiz", icon: Trophy, show: Boolean(analysis) && isLecture },
  ].filter((x) => x.show !== false);
  const current = tabs.some((x) => x.id === tab) ? tab : "report";

  function exportPdf() {
    const before = document.title;
    document.title = `${title} · AI Notebook`;
    const restore = () => { document.title = before; window.removeEventListener("afterprint", restore); };
    window.addEventListener("afterprint", restore);
    window.print();
  }

  const rerun = canResummarize ? <Rerun r={r} currentType={type} /> : null;

  return (
    <>
      <div className="print:hidden">
        <Frame>
          <div className="flex flex-col gap-4">
            <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between sm:gap-6">
              <div className="min-w-0">
                <div className="mb-2 flex flex-wrap items-center gap-2">
                  <TypeBadge type={type} />
                  {st.key !== "ready" && <Chip tone={st.tone} dot={st.dot}>{t(`status.${st.key}`)}</Chip>}
                </div>
                <h1 className="break-words">{title}</h1>
                {meta.length > 0 && (
                  <p className="num mt-2 flex flex-wrap gap-x-2 text-sm text-ink-muted">
                    {meta.map((m, i) => <span key={i}>{i > 0 && <span className="mr-2 text-ink-faint">·</span>}{m}</span>)}
                  </p>
                )}
              </div>
            </div>
            {ready && (
              <div className="flex flex-wrap items-center gap-2">
                <Btn size="sm" variant="outline" onClick={() => setEmailOpen(true)}>
                  <Mail className="size-4" aria-hidden="true" />
                  Follow-up email
                </Btn>
                <Btn size="sm" variant="outline" onClick={exportPdf}>
                  <Printer className="size-4" aria-hidden="true" />
                  Export PDF
                </Btn>
                <Link to={`/ask?note=${r.id}`} className="btn-secondary btn-sm">
                  <MessagesSquare className="size-4" aria-hidden="true" />
                  Ask about this
                </Link>
                {rerun}
              </div>
            )}
            {analysis?.topics?.length > 0 && <Topics topics={analysis.topics} />}
          </div>

          <StatusBanner r={r} busy={busy} run={run} manages={manages} />

          {r.resummarize?.state === "running" && (
            <p className="rounded-2xl bg-primary-tint px-4 py-2.5 text-sm font-semibold text-primary">
              {t("resummarize.running", { model: r.resummarize.model })}
            </p>
          )}
          {r.resummarize?.state === "failed" && (
            <p className="rounded-2xl bg-record-tint px-4 py-2.5 text-sm text-record">
              {t("resummarize.failed", { model: r.resummarize.model })} {r.resummarize.error?.message}
            </p>
          )}

          <div role="tablist" aria-label="Note sections" className="no-scrollbar -mx-4 -mb-2 flex gap-1 overflow-x-auto border-b border-line px-4 sm:mx-0 sm:px-0">
            {tabs.map((x) => {
              const on = current === x.id;
              return (
                <button
                  key={x.id}
                  type="button"
                  role="tab"
                  aria-selected={on}
                  onClick={() => setTab(x.id)}
                  className={cn(
                    "relative inline-flex h-11 shrink-0 items-center gap-2 rounded-t-xl px-3 text-sm font-semibold transition-colors",
                    on ? "text-primary" : "text-ink-muted hover:text-ink",
                  )}
                >
                  <x.icon className="size-4" aria-hidden="true" />
                  {x.label}
                  {on && <span className="absolute inset-x-2 -bottom-px h-0.5 rounded-full bg-primary" aria-hidden="true" />}
                </button>
              );
            })}
          </div>

          <div className={cn("grid items-start gap-4 lg:gap-6", current !== "mindmap" && "lg:grid-cols-[minmax(0,1fr)_340px]")}>
            <div className="flex min-w-0 flex-col gap-4" role="tabpanel">
              {current === "report" && (
                <>
                  {(summaries.length > 1 || summary) && (
                    <div className="flex flex-col gap-3">
                      {summaries.length > 1 && (
                        <div className="no-scrollbar -mx-4 flex gap-2 overflow-x-auto px-4 sm:mx-0 sm:flex-wrap sm:px-0">
                          {summaries.map((s) => (
                            <button key={s.id} type="button" aria-pressed={summary?.id === s.id} onClick={() => setChosen(s.id)} className="pill">
                              {s.model}
                              {s.id === r.summaryId && <span className="text-xs opacity-70">{t("summaries.main")}</span>}
                            </button>
                          ))}
                        </div>
                      )}
                      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
                        {summary && (
                          <p className="num text-xs text-ink-faint">
                            {t("summaries.madeBy", { model: summary.model, when: summary.createdAt ? dateTime(toMillis(summary.createdAt)) : "" })}
                            {summary.requestedByName && ` · ${t("summaries.requestedBy", { name: summary.requestedByName })}`}
                          </p>
                        )}
                        {manages && summary && summary.id !== r.summaryId && (
                          <Btn size="sm" variant="outline" className="sm:ml-auto" loading={busy === "primary"}
                            onClick={() => run("primary", () => setPrimarySummary(r.id, summary.id), t("summaries.madeMain"))}>
                            <Check className="size-4" aria-hidden="true" />
                            {t("summaries.makeMain")}
                          </Btn>
                        )}
                      </div>
                    </div>
                  )}

                  {analysis ? (
                    <>
                      <TypeSections analysis={analysis} />
                      <SummaryView analysis={analysis} names={names} onSeek={hasAudio ? seek : undefined} />
                    </>
                  ) : (
                    <section className="card card-pad flex flex-col gap-2">
                      <h2>{t("detail.summary")}</h2>
                      <p className="text-sm text-ink-muted">
                        {r.noSpeech ? t("detail.noSpeech") : r.status === "deleted" ? t("detail.deletedBody") : t("detail.noSummaryYet")}
                      </p>
                    </section>
                  )}

                  <Health r={r} />
                  {seesLog && <AccessLog entries={access} />}
                  {manages && r.status !== "deleted" && <DangerZone r={r} busy={busy} run={run} />}
                </>
              )}

              {current === "transcript" && (
                <section className="card card-pad flex flex-col gap-4">
                  <div className="flex items-center justify-between gap-3">
                    <h2>{t("detail.transcript")}</h2>
                    {hasTranscript && (
                      <Btn size="sm" variant="ghost" onClick={downloadTranscript} loading={busy === "download-transcript"}>
                        <Download className="size-4" aria-hidden="true" />
                        {t("detail.downloadTranscript")}
                      </Btn>
                    )}
                  </div>
                  <div className="flex max-h-[70vh] min-h-0 flex-col">
                    {hasTranscript ? (
                      <Transcript
                        transcript={transcript}
                        names={names}
                        currentTime={position}
                        onSeek={seek}
                        loading={loadingTranscript}
                        onLoad={loadTranscript}
                      />
                    ) : (
                      <p className="text-sm text-ink-muted">{t("transcript.none")}</p>
                    )}
                  </div>
                </section>
              )}

              {current === "mindmap" && <MindMap map={analysis?.mind_map} action={rerun} />}
              {current === "study" && <StudyNotes notes={analysis?.study_notes} terms={analysis?.key_terms} />}
              {current === "cards" && <Flashcards cards={analysis?.flashcards} />}
              {current === "quiz" && <Quiz questions={analysis?.quiz} />}
            </div>

            {/* The mind map takes the full width; the player stays mounted so a cued moment is kept. */}
            <div className={cn("flex flex-col gap-4 lg:sticky lg:top-24", current === "mindmap" && "hidden")}>
              <section className="card card-pad flex flex-col gap-4">
                <div className="flex items-center justify-between gap-3">
                  <h2>{t("detail.audio")}</h2>
                  {hasAudio && (
                    <Btn size="sm" variant="ghost" onClick={downloadAudio} loading={busy === "download-audio"}>
                      <Download className="size-4" aria-hidden="true" />
                      {t("detail.downloadAudio")}
                    </Btn>
                  )}
                </div>
                <Player ref={player} recordingId={r.id} available={hasAudio} onTime={setPosition} />
                {startAt !== null && hasAudio && (
                  <button type="button" onClick={() => seek(startAt)}
                    className="num inline-flex h-8 items-center gap-1.5 self-start rounded-full bg-primary-tint px-3 text-xs font-bold text-primary hover:bg-primary hover:text-white">
                    <Play className="size-3.5" aria-hidden="true" />
                    Play from {clock(startAt)}
                  </button>
                )}
              </section>
              <TalkTime talkTime={talkTime} names={names} />
            </div>
          </div>
        </Frame>
      </div>

      {analysis && (
        <PrintReport title={title} meta={meta} type={type} analysis={analysis} names={names} talkTime={talkTime} />
      )}
      {ready && <EmailModal open={emailOpen} onClose={() => setEmailOpen(false)} recordingId={r.id} />}
    </>
  );
}

function Frame({ children }) {
  return (
    <div className="flex flex-col gap-6">
      <Link to="/recordings" className="inline-flex h-8 items-center gap-1.5 self-start rounded-full pr-3 text-sm font-semibold text-ink-muted hover:text-primary">
        <ArrowLeft className="size-4" aria-hidden="true" />
        {t("detail.back")}
      </Link>
      {children}
    </div>
  );
}

function DetailSkeleton() {
  return (
    <div className="flex flex-col gap-6" role="status" aria-label={t("loading")}>
      <div className="flex flex-col gap-3">
        <Skeleton className="h-7 w-24 rounded-full" />
        <Skeleton className="h-8 w-2/3" />
        <Skeleton className="h-4 w-1/3" />
      </div>
      <Skeleton className="h-11 w-full max-w-lg" />
      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_340px] lg:gap-6">
        <div className="card flex flex-col gap-3 p-6">
          <Skeleton className="h-5 w-28" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-11/12" />
          <Skeleton className="h-4 w-4/5" />
        </div>
        <div className="card flex flex-col gap-3 p-6">
          <Skeleton className="h-5 w-20" />
          <Skeleton className="h-10 w-full rounded-full" />
        </div>
      </div>
    </div>
  );
}

/** Why a recording is not ready yet, or why it failed, and what an admin can do about it. */
function StatusBanner({ r, busy, run, manages }) {
  const { dateTime } = useFormat();
  const [arrived, setArrived] = useState(null);
  const [confirming, setConfirming] = useState(false);

  if (r.status === "failed") {
    const transcriptKept = Boolean(r.transcription?.receivedAt);
    return (
      <div role="alert" className="flex flex-col gap-3 rounded-card bg-record-tint px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="min-w-0">
          <p className="text-sm font-bold text-record">{errorLabel(t, r.error?.code ?? "unknown")}</p>
          <p className="mt-0.5 text-xs text-ink-muted">
            {t("failed.where", { step: t(`stepName.${r.error?.step ?? "unknown"}`, { defaultValue: r.error?.step ?? "-" }), when: r.error?.at ? dateTime(toMillis(r.error.at)) : "-" })}
            {r.error?.message && ` ${r.error.message}`}
          </p>
        </div>
        {manages && (
          <div className="flex flex-wrap gap-2">
            <Btn size="sm" loading={busy === "retry"} onClick={() => run("retry", () => retryRecording(r.id), t("failed.retried"))}>
              <RotateCcw className="size-4" aria-hidden="true" />
              {t("failed.retry")}
            </Btn>
            {transcriptKept && (
              <Btn size="sm" variant="outline" loading={busy === "fresh"} onClick={() => run("fresh", () => retryRecording(r.id, true), t("failed.retried"))}>
                {t("failed.retryFresh")}
              </Btn>
            )}
          </div>
        )}
      </div>
    );
  }

  if (r.status === "uploading") {
    return (
      <div className="flex flex-col gap-3 rounded-card bg-amber-tint px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="min-w-0">
          <p className="text-sm font-bold text-amber">{t("uploading.body")}</p>
          {arrived && (
            <p className="mt-0.5 text-xs text-ink-muted">
              {t("uploadProgress", { count: arrived.chunks, when: arrived.lastAt ? dateTime(toMillis(arrived.lastAt)) : "-" })}
            </p>
          )}
        </div>
        {manages && (
          <div className="flex flex-wrap gap-2">
            {!arrived && (
              <Btn size="sm" variant="outline" loading={busy === "progress"}
                onClick={() => run("progress", async () => {
                  const { progress } = await uploadProgress([r.id]);
                  setArrived(progress?.[r.id] ?? { chunks: 0, lastAt: null });
                })}>
                {t("uploading.check")}
              </Btn>
            )}
            <Btn size="sm" variant="outline" onClick={() => setConfirming(true)} loading={busy === "process"}>{t("uploading.process")}</Btn>
          </div>
        )}
        <Confirm
          open={confirming}
          title={t("uploading.confirmTitle")}
          confirmLabel={t("uploading.process")}
          cancelLabel={t("common:action.cancel")}
          onCancel={() => setConfirming(false)}
          onConfirm={() => { setConfirming(false); run("process", () => processWhatArrived(r.id), t("uploading.processed")); }}
        >
          {t("uploading.confirmBody")}
        </Confirm>
      </div>
    );
  }

  if (r.status === "processing" || r.status === "uploaded") {
    const steps = ["join", "transcribe", "summarize"];
    const at = r.status === "uploaded" ? -1 : steps.indexOf(r.step);
    return (
      <div className="card flex flex-col gap-3 px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
        <p className="text-sm font-semibold">{t(`processing.${statusOf(r).detail ?? "queued"}`)}</p>
        <ol className="flex flex-wrap items-center gap-2">
          {steps.map((s, i) => (
            <li key={s} className={cn(
              "inline-flex h-7 items-center gap-1.5 rounded-full px-3 text-xs font-semibold",
              i < at && "bg-primary-tint text-primary",
              i === at && "bg-primary text-white",
              i > at && "bg-surface-alt text-ink-faint",
            )}>
              {i < at ? <Check className="size-3.5" aria-hidden="true" /> : i === at ? <span className="live-dot size-1.5 rounded-full bg-white" aria-hidden="true" /> : null}
              {t(`stepName.${s}`)}
            </li>
          ))}
        </ol>
      </div>
    );
  }

  if (r.status === "deleted") {
    return (
      <div className="rounded-card bg-surface-alt px-5 py-4 text-sm font-medium text-ink-muted">
        {t("deleted.body", { name: r.deletedByName ?? "-", when: r.deletedAt ? dateTime(toMillis(r.deletedAt)) : "-" })}
      </div>
    );
  }
  return null;
}

/** How the recording went, as the phone and the pipeline saw it. */
function Health({ r }) {
  const { dateTime } = useFormat();
  const started = r.startedAt ?? r.meetingAt;
  const gaps = r.gaps ?? [];
  const tx = r.transcription ?? {};

  return (
    <section className="card card-pad flex flex-col gap-5">
      <h2>{t("health.title")}</h2>
      <dl className="grid gap-x-6 gap-y-4 text-sm sm:grid-cols-2">
        <Row label={t("health.recorded")} value={started ? dateTime(started) : null} />
        <Row label={t("health.ended")} value={r.endedAt ? `${dateTime(r.endedAt)}, ${t(`endReason.${r.endReason}`, { defaultValue: r.endReason ?? "" })}` : null} />
        <Row label={t("health.length")} value={duration(t, r.durationMs) || (tx.audioSecs ? clock(tx.audioSecs) : null)} />
        <Row label={t("health.chunks")} value={r.chunkCount ? t("health.chunkCount", { count: r.chunkCount }) : null} />
        <Row label={t("health.uploaded")} value={r.uploadedAt ? dateTime(toMillis(r.uploadedAt)) : null} />
        <Row label={t("health.ready")} value={r.readyAt ? dateTime(toMillis(r.readyAt)) : null} />
        <Row label={t("health.device")} value={[r.platform, r.deviceModel, r.appVersion && `v${r.appVersion}`].filter(Boolean).join(" · ") || null} />
        <Row label={t("health.transcription")} value={tx.savedAt ? t("health.transcriptionValue", { language: tx.language ?? "-", speakers: tx.speakers ?? 0, words: tx.words ?? 0 }) : null} />
        <Row label={t("health.run")} value={r.run > 1 ? t("health.runs", { count: r.run, name: r.retriedByName ?? "-" }) : null} />
        <Row label={t("health.forced")} value={r.forcedByName ? t("health.forcedBy", { name: r.forcedByName }) : null} />
      </dl>

      <div className="flex flex-col gap-2">
        <h3 className="label">{t("health.pauses")}</h3>
        {gaps.length === 0 ? (
          <p className="text-sm text-ink-muted">{t("health.noPauses")}</p>
        ) : (
          <ul className="flex flex-col gap-1.5 text-sm">
            {gaps.map((g, i) => (
              <li key={i} className="flex flex-wrap gap-x-3">
                <span className="num font-semibold">
                  {started ? timeOfDay(started + g.startMs) : clock(g.startMs / 1000)}
                  {" to "}
                  {g.endMs === null || g.endMs === undefined ? "?" : started ? timeOfDay(started + g.endMs) : clock(g.endMs / 1000)}
                </span>
                <span className="text-ink-muted">{t(`gap.${g.reason}`, { defaultValue: g.reason })}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}

function AccessLog({ entries }) {
  const { dateTime } = useFormat();
  return (
    <section className="card card-pad flex flex-col gap-4">
      <h2>{t("access.title")}</h2>
      {entries.length === 0 ? (
        <p className="text-sm text-ink-muted">{t("access.none")}</p>
      ) : (
        <ul className="flex flex-col divide-y divide-line text-sm">
          {entries.map((e) => (
            <li key={e.id} className="flex flex-wrap items-baseline gap-x-2 py-2.5 first:pt-0 last:pb-0">
              <span className="font-semibold">{e.name || e.uid}</span>
              <span className="text-ink-muted">{t(`access.${e.action}`, { defaultValue: e.action })}</span>
              <span className="num ml-auto text-xs text-ink-faint">{e.at ? dateTime(toMillis(e.at)) : ""}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function DangerZone({ r, busy, run }) {
  const [confirming, setConfirming] = useState(false);
  const remove = () => {
    setConfirming(false);
    run("delete", () => deleteRecording(r.id), t("delete.done"));
  };
  return (
    <section className="card card-pad flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
      <div>
        <h2>{t("delete.title")}</h2>
        <p className="mt-1 text-sm text-ink-muted">{t("delete.body")}</p>
      </div>
      <Btn variant="destructive" loading={busy === "delete"} onClick={() => setConfirming(true)} className="self-start sm:self-auto">
        <Trash2 className="size-4" aria-hidden="true" />
        {t("delete.button")}
      </Btn>
      <Confirm
        open={confirming}
        danger
        title={t("delete.confirmTitle")}
        confirmLabel={t("delete.button")}
        cancelLabel={t("common:action.cancel")}
        onCancel={() => setConfirming(false)}
        onConfirm={remove}
      >
        {t("delete.confirmBody")}
      </Confirm>
    </section>
  );
}

function Row({ label, value }) {
  if (!value) return null;
  return (
    <div className="min-w-0">
      <dt className="label">{label}</dt>
      <dd className="mt-1 break-words">{value}</dd>
    </div>
  );
}

const TIME = new Intl.DateTimeFormat("en-IN", { hour: "numeric", minute: "2-digit", numberingSystem: "latn" });
const timeOfDay = (ms) => TIME.format(new Date(ms));

/** `meeting-2026-09-25-name-of-recorder`, the same name the audio download gets. */
function fileBase(r) {
  const when = r.meetingAt ? new Date(r.meetingAt + 5.5 * 3600 * 1000).toISOString().slice(0, 10) : "";
  const who = String(r.ownerName ?? "").normalize("NFKD").replace(/[^A-Za-z0-9]+/g, "-").replace(/^-+|-+$/g, "").toLowerCase().slice(0, 40);
  return ["meeting", when, who || r.id.slice(0, 8)].filter(Boolean).join("-");
}
