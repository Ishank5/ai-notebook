import { Quote, Sparkles, Tag } from "lucide-react";
import { Link } from "react-router-dom";

import { BarList } from "../../components/charts";
import { CardHead } from "../../components/ui";

/** "12 min", "45 s", "1 h 05 min". */
// eslint-disable-next-line react-refresh/only-export-components
export function spoken(secs) {
  const s = Math.round(secs ?? 0);
  if (s < 60) return `${s} s`;
  const m = Math.round(s / 60);
  return m < 60 ? `${m} min` : `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, "0")} min`;
}

/** Talk time per speaker from `talkTime`, or worked out from the transcript when it is loaded. */
// eslint-disable-next-line react-refresh/only-export-components
export function talkTimeOf(recording, transcript) {
  if (Array.isArray(recording?.talkTime) && recording.talkTime.length) return recording.talkTime;
  if (!transcript?.turns?.length) return [];
  const totals = new Map();
  for (const t of transcript.turns) {
    if (!t?.s || t.s === "?") continue;
    totals.set(t.s, (totals.get(t.s) ?? 0) + Math.max(0, (t.t1 ?? t.t0) - t.t0));
  }
  return [...totals].map(([speaker, seconds]) => ({ speaker, seconds: Math.round(seconds) })).sort((a, b) => b.seconds - a.seconds);
}

/** Who spoke how much, with names from the summary's participants. */
export function TalkTime({ talkTime, names }) {
  const rows = (talkTime ?? []).filter((x) => x.seconds > 0);
  if (rows.length === 0) return null;
  const items = rows.slice(0, 8).map((x) => ({
    key: x.speaker,
    label: names.get(x.speaker) ? `${names.get(x.speaker)}` : x.speaker,
    value: x.seconds,
    figure: spoken(x.seconds),
  }));
  return (
    <section className="card card-pad flex flex-col gap-4">
      <CardHead title="Talk time" count={rows.length} />
      <BarList items={items} />
    </section>
  );
}

/** The note's topic tags, each opening the notes that share it. */
export function Topics({ topics }) {
  const list = (topics ?? []).filter(Boolean);
  if (!list.length) return null;
  return (
    <ul className="flex flex-wrap gap-1.5">
      {list.map((topic) => (
        <li key={topic}>
          <Link
            to={`/recordings?topic=${encodeURIComponent(topic)}&range=quarter`}
            className="inline-flex h-7 items-center gap-1 rounded-full bg-surface-alt px-2.5 text-xs font-semibold text-ink-muted transition-colors hover:bg-primary-tint hover:text-primary"
          >
            <Tag className="size-3" aria-hidden="true" />
            {topic}
          </Link>
        </li>
      ))}
    </ul>
  );
}

/** A voice note's clean rewrite, and a journal's highlights and reflection, above the usual report. */
export function TypeSections({ analysis }) {
  const body = String(analysis?.note_body ?? "").trim();
  const highlights = (analysis?.highlights ?? []).filter(Boolean);
  const reflection = String(analysis?.reflection ?? "").trim();
  if (!body && !highlights.length && !reflection) return null;
  return (
    <>
      {body && (
        <section className="card card-pad flex flex-col gap-3">
          <h2>Note</h2>
          {body.split(/\n\s*\n/).map((p, i) => <p key={i} className="whitespace-pre-wrap text-[15px] leading-relaxed">{p}</p>)}
        </section>
      )}
      {highlights.length > 0 && (
        <section className="card card-pad flex flex-col gap-3">
          <CardHead title="Highlights" icon={Sparkles} count={highlights.length} />
          <ul className="flex flex-col gap-2">
            {highlights.map((h, i) => (
              <li key={i} className="flex gap-3 text-sm leading-relaxed">
                <span className="mt-2 size-1.5 shrink-0 rounded-full bg-journal" aria-hidden="true" />
                {h}
              </li>
            ))}
          </ul>
        </section>
      )}
      {reflection && (
        <section className="flex gap-3 rounded-card border border-journal/20 bg-journal-tint px-5 py-5 sm:px-6">
          <Quote className="mt-0.5 size-5 shrink-0 text-journal" aria-hidden="true" />
          <div className="flex flex-col gap-1">
            <h2 className="text-journal">Reflection</h2>
            <p className="whitespace-pre-wrap text-[15px] leading-relaxed text-ink">{reflection}</p>
          </div>
        </section>
      )}
    </>
  );
}
