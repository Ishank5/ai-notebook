import { CalendarDays, ChevronRight, Layers, Mic, Search, Tag, User, X } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";

import { NOTE_TYPES, TypeBadge, noteTypeOf } from "../../components/NoteType";

import { Chip, EmptyState, ErrorState, Loading, Select, cn } from "../../components/ui";
import { useAuth } from "../../lib/auth";
import { uploadProgress } from "./api";
import { useFormat } from "./fmt";
import { duration, errorLabel, statusOf, titleOf, toMillis } from "./format";
import { t } from "./strings";
import { MAX_ROWS, useRecordings } from "./useRecordings";

const RANGES = { today: 0, week: 7, month: 30, quarter: 90 };
const STATUSES = ["all", "uploading", "processing", "ready", "failed", "deleted"];

/**
 * Every meeting, newest first: when, who, how long, what it was, and where it has got to.
 * Members see their own; admins see everyone's, with the person filter and upload progress.
 * Every filter works on what the date range loaded, so changing one is instant.
 */
export default function Recordings() {
  const { dateTime, number } = useFormat();
  const { can } = useAuth();
  const seesAll = can("recordings.read_all");
  const manages = can("recordings.manage");

  const [params, setParams] = useSearchParams();
  const [rangeKey, setRangeKey] = useState(() => (params.get("range") in RANGES ? params.get("range") : "week"));
  const [type, setType] = useState(() => params.get("type") ?? "");
  const topic = params.get("topic") ?? "";
  const clearTopic = () => setParams((p) => { p.delete("topic"); return p; }, { replace: true });
  const [onDate, setOnDate] = useState("");
  const [status, setStatus] = useState("all");
  const [ownerUid, setOwnerUid] = useState("");
  const [search, setSearch] = useState("");

  const range = useMemo(() => rangeOf(rangeKey, onDate), [rangeKey, onDate]);
  const { status: loadState, rows, truncated, reload } = useRecordings(range);

  const people = useMemo(() => {
    const byUid = new Map();
    for (const r of rows) if (r.ownerUid && !byUid.has(r.ownerUid)) byUid.set(r.ownerUid, r.ownerName || r.ownerUid);
    return [...byUid.entries()].sort((a, b) => a[1].localeCompare(b[1]));
  }, [rows]);

  const shown = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return rows.filter((r) => {
      const s = statusOf(r).key;
      if (status === "all" ? s === "deleted" : status === "ready" ? !["ready", "noSpeech"].includes(s) : s !== status) return false;
      if (ownerUid && r.ownerUid !== ownerUid) return false;
      if (type && noteTypeOf(r) !== type) return false;
      if (topic && !(r.topics ?? []).some((x) => x.toLowerCase() === topic.toLowerCase())) return false;
      if (needle) {
        const hay = [r.title, r.summaryTitle, r.summaryText, r.ownerName, ...(r.topics ?? [])].filter(Boolean).join(" ").toLowerCase();
        if (!hay.includes(needle)) return false;
      }
      return true;
    });
  }, [rows, status, ownerUid, search, type, topic]);

  const counts = useMemo(() => {
    const c = Object.fromEntries(STATUSES.map((s) => [s, 0]));
    for (const r of rows) {
      const s = statusOf(r).key;
      const bucket = s === "noSpeech" ? "ready" : s;
      if (bucket in c) c[bucket]++;
      if (s !== "deleted") c.all++;
    }
    return c;
  }, [rows]);

  const progress = useUploadProgress(manages ? shown : []);
  const grid = seesAll
    ? "md:grid-cols-[minmax(0,1fr)_150px_170px_80px_120px_20px]"
    : "md:grid-cols-[minmax(0,1fr)_170px_80px_120px_20px]";

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-baseline gap-3">
        <h1>{t("title")}</h1>
        {loadState === "ready" && <span className="num text-sm font-semibold text-ink-faint">{number(counts.all)}</span>}
      </div>

      <div className="flex flex-col gap-3">
        <div className="flex flex-col gap-3 md:flex-row md:items-center">
          <div className="relative md:max-w-sm md:flex-1">
            <Search className="pointer-events-none absolute left-4 top-1/2 size-4 -translate-y-1/2 text-ink-faint" aria-hidden="true" />
            <input
              type="search"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder={t("filter.search")}
              aria-label={t("filter.search")}
              className="field border-line bg-surface pl-11"
            />
          </div>
          <div className="no-scrollbar -mx-4 flex items-center gap-2 overflow-x-auto px-4 md:mx-0 md:px-0">
            <Select
              icon={CalendarDays}
              aria-label={t("filter.when")}
              value={onDate ? "date" : rangeKey}
              onChange={(key) => { if (key !== "date") { setRangeKey(key); setOnDate(""); } }}
              options={[
                ...Object.keys(RANGES).map((key) => ({ value: key, label: t(`range.${key}`) })),
                ...(onDate ? [{ value: "date", label: dayLabel(onDate) }] : []),
              ]}
            />
            <label className="pill relative cursor-pointer" title={t("filter.onDate")}>
              <span className="sr-only">{t("filter.onDate")}</span>
              <input
                type="date"
                value={onDate}
                max={todayValue()}
                onChange={(e) => setOnDate(e.target.value)}
                className="w-[118px] bg-transparent text-ink outline-none"
              />
            </label>
            {onDate && (
              <button type="button" className="pill" onClick={() => setOnDate("")} aria-label="Clear date">
                <X className="size-4" aria-hidden="true" />
              </button>
            )}
            <Select
              icon={Layers}
              aria-label={t("filter.type")}
              value={type}
              onChange={setType}
              options={[{ value: "", label: t("filter.allTypes") }, ...NOTE_TYPES.map((n) => ({ value: n.id, label: n.label }))]}
            />
            {topic && (
              <button type="button" className="pill border-primary-tint bg-primary-tint text-primary" onClick={clearTopic} aria-label={`Clear topic ${topic}`}>
                <Tag className="size-3.5" aria-hidden="true" />
                {topic}
                <X className="size-3.5" aria-hidden="true" />
              </button>
            )}
            {seesAll && (
              <Select
                icon={User}
                aria-label={t("filter.person")}
                value={ownerUid}
                onChange={setOwnerUid}
                disabled={people.length === 0}
                options={[{ value: "", label: t("filter.everyone") }, ...people.map(([id, name]) => ({ value: id, label: name }))]}
              />
            )}
          </div>
        </div>

        <div className="no-scrollbar -mx-4 flex gap-2 overflow-x-auto px-4 md:mx-0 md:flex-wrap md:px-0" role="group" aria-label={t("filter.status")}>
          {STATUSES.map((s) => (
            <button key={s} type="button" aria-pressed={status === s} onClick={() => setStatus(s)} className="pill">
              {t(`statusFilter.${s}`)}
              <span className={cn("num text-xs", status === s ? "text-primary/70" : "text-ink-faint")}>{number(counts[s] ?? 0)}</span>
            </button>
          ))}
        </div>
      </div>

      {truncated && (
        <p className="rounded-2xl bg-amber-tint px-4 py-3 text-sm font-semibold text-amber">{t("truncated", { count: MAX_ROWS })}</p>
      )}

      {loadState === "loading" && rows.length === 0 && <div className="card"><Loading rows={5} label={t("loading")} /></div>}
      {loadState === "error" && <ErrorState title={t("errorTitle")} body={t("errorBody")} onRetry={reload} />}
      {loadState === "ready" && shown.length === 0 && (
        <EmptyState icon={Mic} title={rows.length === 0 ? t("emptyTitle") : t("noMatchTitle")} hint={rows.length === 0 ? t("emptyBody") : t("noMatchBody")} />
      )}

      {shown.length > 0 && (
        <div className="card overflow-hidden">
          <div className={cn("hidden gap-4 border-b border-line px-6 py-3 text-xs font-semibold text-ink-faint md:grid", grid)}>
            <span>{t("column.meeting")}</span>
            {seesAll && <span>{t("column.by")}</span>}
            <span>{t("column.when")}</span>
            <span>{t("column.length")}</span>
            <span>{t("column.status")}</span>
            <span />
          </div>
          <ul className="divide-y divide-line">
            {shown.map((r) => {
              const st = statusOf(r);
              const title = titleOf(r);
              const p = progress.get(r.id);
              const note = r.status === "uploading" && p
                ? <span className="text-amber">{t("uploadProgress", { count: p.chunks, when: p.lastAt ? dateTime(p.lastAt) : "-" })}</span>
                : r.status === "failed" && r.error?.code
                  ? <span className="text-record">{errorLabel(t, r.error.code)}</span>
                  : st.detail
                    ? <span>{t(`step.${st.detail}`)}</span>
                    : null;
              return (
                <li key={r.id}>
                  <Link
                    to={`/recordings/${r.id}`}
                    className={cn("group grid gap-x-4 gap-y-1.5 px-5 py-4 transition-colors hover:bg-bg focus-visible:bg-bg focus-visible:outline-none md:items-center md:px-6", grid)}
                  >
                    <div className="min-w-0">
                      <div className="flex items-start justify-between gap-3">
                        <span className="flex min-w-0 items-center gap-2">
                          <span className={cn("min-w-0 truncate text-[15px] font-bold", !title && "text-ink-muted")}>
                            {title || t("untitled")}
                          </span>
                          <TypeBadge type={noteTypeOf(r)} size="sm" className="max-sm:hidden" />
                        </span>
                        <Chip tone={st.tone} size="sm" dot={st.dot} className="md:hidden">{t(`status.${st.key}`)}</Chip>
                      </div>
                      {r.summaryText && <p className="mt-1 line-clamp-1 text-[13px] text-ink-muted">{r.summaryText}</p>}
                      {note && <p className="mt-1 text-xs font-semibold text-ink-muted">{note}</p>}
                      <p className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-ink-faint md:hidden">
                        <TypeBadge type={noteTypeOf(r)} size="sm" className="sm:hidden" />
                        {seesAll && <span className="font-semibold text-ink-muted">{r.ownerName || t("unnamed")}</span>}
                        {r.meetingAt && <span className="num">{dateTime(r.meetingAt)}</span>}
                        {r.durationMs > 0 && <span className="num">{duration(t, r.durationMs)}</span>}
                      </p>
                    </div>
                    {seesAll && <span className="hidden truncate text-sm font-medium md:block">{r.ownerName || t("unnamed")}</span>}
                    <span className="num hidden text-sm text-ink-muted md:block">{r.meetingAt ? dateTime(r.meetingAt) : "-"}</span>
                    <span className="num hidden text-sm text-ink-muted md:block">{r.durationMs > 0 ? duration(t, r.durationMs) : "-"}</span>
                    <span className="hidden md:block"><Chip tone={st.tone} size="sm" dot={st.dot}>{t(`status.${st.key}`)}</Chip></span>
                    <ChevronRight className="hidden size-4 text-ink-faint transition-transform group-hover:translate-x-0.5 md:block" aria-hidden="true" />
                  </Link>
                </li>
              );
            })}
          </ul>
        </div>
      )}
    </div>
  );
}

function todayValue() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

/** "2026-11-23" as "23 Nov 2026". */
function dayLabel(value) {
  const [y, m, d] = value.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });
}

/** A named range, or one calendar day, as local-time milliseconds. */
function rangeOf(key, onDate) {
  if (onDate) {
    const [y, m, d] = onDate.split("-").map(Number);
    const start = new Date(y, m - 1, d).getTime();
    return { fromMillis: start, toMillis: start + 24 * 3600 * 1000 - 1 };
  }
  const now = new Date();
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  // The end is open: a phone clock can run a little fast, and the range is only recomputed when a filter changes.
  return { fromMillis: startOfToday - RANGES[key] * 24 * 3600 * 1000, toMillis: startOfToday + 2 * 24 * 3600 * 1000 };
}

/** How much of each still-uploading recording on screen has arrived. Asked once, then every two minutes. */
function useUploadProgress(rows) {
  const ids = rows.filter((r) => r.status === "uploading").map((r) => r.id).slice(0, 50);
  const key = ids.join(",");
  const [progress, setProgress] = useState(() => new Map());

  useEffect(() => {
    if (!key) return undefined;
    let live = true;
    const fetchNow = async () => {
      try {
        const { progress: p } = await uploadProgress(key.split(","));
        if (live) setProgress(new Map(Object.entries(p ?? {}).map(([id, v]) => [id, { ...v, lastAt: toMillis(v.lastAt) }])));
      } catch {
        // Progress is a courtesy; the row still says Uploading without it.
      }
    };
    const first = setTimeout(fetchNow, 0);
    const every = setInterval(fetchNow, 120_000);
    return () => {
      live = false;
      clearTimeout(first);
      clearInterval(every);
    };
  }, [key]);

  return progress;
}
