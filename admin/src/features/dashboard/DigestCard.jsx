import { ArrowUpRight, CircleHelp, Gavel, Loader2, RefreshCw, Sparkles } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { toast } from "sonner";

import { CardHead, Segmented, Skeleton } from "../../components/ui";
import { friendlyError } from "../../lib/api";
import { timeAgo } from "../../lib/dates";
import { generateInsights, getInsights } from "./api";

const RANGES = [
  { value: "today", label: "Today", phrase: "today" },
  { value: "week", label: "Week", phrase: "this week" },
  { value: "month", label: "Month", phrase: "this month" },
];

/**
 * The AI's read of a period: a short digest, the themes that keep coming up, open questions and
 * decisions, each linked to its note. The cached digest shows at once; the first view of a range
 * writes one, and Refresh writes a new one.
 */
export function DigestCard({ titles }) {
  const [range, setRange] = useState("week");
  const [byRange, setByRange] = useState({});
  const inflight = useRef(new Set());
  const entry = byRange[range] ?? { status: "loading" };

  const put = (r, value) => setByRange((m) => ({ ...m, [r]: value }));

  async function generate(r) {
    if (inflight.current.has(`gen:${r}`)) return;
    inflight.current.add(`gen:${r}`);
    put(r, { status: "generating", insights: byRange[r]?.insights ?? null });
    try {
      put(r, { status: "ready", insights: await generateInsights(r) });
    } catch (err) {
      toast.error(friendlyError(err, "Could not write the digest. Try again."));
      setByRange((m) => ({ ...m, [r]: m[r]?.insights ? { status: "ready", insights: m[r].insights } : { status: "error" } }));
    } finally {
      inflight.current.delete(`gen:${r}`);
    }
  }

  useEffect(() => {
    if (byRange[range] || inflight.current.has(range)) return;
    inflight.current.add(range);
    getInsights(range)
      .then((insights) => {
        if (insights) put(range, { status: "ready", insights });
        else generate(range);
      })
      .catch(() => put(range, { status: "error" }))
      .finally(() => inflight.current.delete(range));
    // generate reads the latest state through setByRange; only the range matters here.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [range, byRange[range]]);

  const ins = entry.insights;
  const phrase = RANGES.find((r) => r.value === range).phrase;
  const busy = entry.status === "generating";

  return (
    <section className="card card-pad flex flex-col gap-4">
      <CardHead
        title="AI digest"
        icon={Sparkles}
        action={<Segmented size="sm" aria-label="Period" value={range} onChange={setRange} options={RANGES} />}
      />

      {(entry.status === "ready" || busy) && ins && (
        <div className="-mt-1 flex flex-wrap items-center justify-between gap-2 text-xs text-ink-faint">
          <span className="num">
            {ins.noteCount === 1 ? "From 1 note" : `From ${ins.noteCount} notes`}
            {ins.generatedAt ? ` · Updated ${timeAgo(ins.generatedAt)}` : ""}
          </span>
          <button type="button" onClick={() => generate(range)} disabled={busy}
            className="btn-ghost btn-sm -mr-2 h-7 text-xs">
            <RefreshCw className={busy ? "size-3.5 animate-spin" : "size-3.5"} aria-hidden="true" />
            Refresh
          </button>
        </div>
      )}

      {(entry.status === "loading" || (busy && !ins)) && <DigestSkeleton writing={busy} />}
      {busy && ins && <p className="flex items-center gap-2 text-sm font-semibold text-primary"><Loader2 className="size-4 animate-spin" aria-hidden="true" />Writing a fresh digest</p>}

      {entry.status === "error" && (
        <div className="flex flex-col items-start gap-3 rounded-2xl bg-surface-alt p-4">
          <p className="text-sm text-ink-muted">The digest could not load.</p>
          <button type="button" className="btn-secondary btn-sm" onClick={() => generate(range)}>Try again</button>
        </div>
      )}

      {entry.status === "ready" && ins && ins.noteCount === 0 && (
        <p className="rounded-2xl bg-surface-alt px-4 py-5 text-center text-sm text-ink-muted">No notes {phrase} yet.</p>
      )}

      {ins && ins.noteCount > 0 && (
        <div className={busy ? "flex flex-col gap-5 opacity-50 transition-opacity" : "flex flex-col gap-5"}>
          {ins.digest && <p className="text-[15px] leading-relaxed text-ink">{ins.digest}</p>}

          {ins.themes?.length > 0 && (
            <div className="flex flex-col gap-2.5">
              <h3 className="label">Themes</h3>
              <ul className="flex flex-col gap-2">
                {ins.themes.slice(0, 5).map((th, i) => (
                  <li key={i} className="rounded-2xl bg-bg px-4 py-3">
                    <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
                      <span className="text-sm font-bold">{th.theme}</span>
                      {th.recordingIds?.length > 0 && (
                        <span className="num text-xs font-semibold text-ink-faint">
                          {th.recordingIds.length === 1 ? "1 note" : `${th.recordingIds.length} notes`}
                        </span>
                      )}
                    </div>
                    {th.detail && <p className="mt-1 text-[13px] leading-relaxed text-ink-muted">{th.detail}</p>}
                    {th.recordingIds?.length > 0 && (
                      <div className="mt-2 flex flex-wrap gap-1.5">
                        {th.recordingIds.slice(0, 3).map((id) => <NoteChip key={id} id={id} titles={titles} />)}
                      </div>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          )}

          {(ins.openQuestions?.length > 0 || ins.decisions?.length > 0) && (
            <div className="grid gap-5 sm:grid-cols-2">
              <LinkedList icon={CircleHelp} title="Open questions" items={(ins.openQuestions ?? []).map((q) => ({ text: q.question, id: q.recordingId }))} />
              <LinkedList icon={Gavel} title="Decisions" items={(ins.decisions ?? []).map((d) => ({ text: d.text, id: d.recordingId }))} />
            </div>
          )}
        </div>
      )}
    </section>
  );
}

function NoteChip({ id, titles }) {
  return (
    <Link to={`/recordings/${id}`}
      className="inline-flex h-7 max-w-full items-center gap-1 rounded-full border border-line bg-surface px-2.5 text-xs font-semibold text-ink-muted transition-colors hover:border-primary hover:text-primary">
      <span className="truncate">{titles.get(id) || "Open note"}</span>
      <ArrowUpRight className="size-3 shrink-0" aria-hidden="true" />
    </Link>
  );
}

function LinkedList({ icon: Icon, title, items }) {
  if (!items.length) return null;
  return (
    <div className="flex min-w-0 flex-col gap-2">
      <h3 className="label flex items-center gap-1.5"><Icon className="size-3.5" aria-hidden="true" />{title}</h3>
      <ul className="flex flex-col gap-1">
        {items.slice(0, 4).map((item, i) => (
          <li key={i}>
            {item.id ? (
              <Link to={`/recordings/${item.id}`} className="group -mx-2 flex items-start gap-2 rounded-xl px-2 py-1.5 text-[13px] leading-snug text-ink hover:bg-bg">
                <span className="min-w-0 flex-1">{item.text}</span>
                <ArrowUpRight className="mt-0.5 size-3.5 shrink-0 text-ink-faint group-hover:text-primary" aria-hidden="true" />
              </Link>
            ) : (
              <p className="py-1.5 text-[13px] leading-snug">{item.text}</p>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

function DigestSkeleton({ writing }) {
  return (
    <div className="flex flex-col gap-3" role="status" aria-label={writing ? "Writing the digest" : "Loading the digest"}>
      {writing && (
        <p className="flex items-center gap-2 text-sm font-semibold text-primary">
          <Loader2 className="size-4 animate-spin" aria-hidden="true" />
          Reading your notes
        </p>
      )}
      <Skeleton className="h-4 w-full" />
      <Skeleton className="h-4 w-11/12" />
      <Skeleton className="h-4 w-4/5" />
      <div className="mt-2 grid gap-2">
        <Skeleton className="h-16 w-full rounded-2xl" />
        <Skeleton className="h-16 w-full rounded-2xl" />
      </div>
    </div>
  );
}
