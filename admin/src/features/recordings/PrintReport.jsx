import { LogoMark } from "../../components/Logo";
import { noteTypeLabel } from "../../components/NoteType";
import { clock } from "./format";
import { spoken } from "./NoteParts";
import { readAnalysis } from "./Summary";

/*
 * What Export PDF prints: a plain A4 report with the logo, hidden on screen. The app around it is
 * hidden when printing, so the browser's "Save as PDF" gives just this.
 */

const LEVEL = { high: "High", medium: "Medium", low: "Low" };
const at = (s) => (Number.isFinite(s) && s >= 0 ? clock(s) : "");

function Block({ title, children }) {
  return (
    <section className="mt-6">
      <h2 className="mb-2 border-b border-line pb-1 text-[13px] font-extrabold uppercase tracking-wide text-primary [break-after:avoid]">{title}</h2>
      {children}
    </section>
  );
}

function Bullets({ items }) {
  return (
    <ul className="flex flex-col gap-1.5 pl-4 text-[12.5px] leading-relaxed">
      {items.map((x, i) => (
        <li key={i} className="print-avoid list-disc">
          {x.text}
          {at(x.at) && <span className="ml-2 text-[11px] text-ink-faint">{at(x.at)}</span>}
        </li>
      ))}
    </ul>
  );
}

export function PrintReport({ title, meta, type, analysis, names, talkTime }) {
  const a = readAnalysis(analysis);
  const raw = analysis ?? {};
  const topics = (raw.topics ?? []).filter(Boolean);
  const studyNotes = (raw.study_notes ?? []).filter((s) => s?.heading);
  const terms = (raw.key_terms ?? []).filter((x) => x?.term);
  const cards = (raw.flashcards ?? []).filter((x) => x?.front);
  const highlights = (raw.highlights ?? []).filter(Boolean);
  const who = (label) => (label && names?.get(label)) || label;
  const exported = new Date().toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });

  return (
    <article className="hidden bg-white text-ink print:block">
      <header className="flex items-center justify-between border-b-2 border-primary pb-3">
        <span className="flex items-center gap-2">
          <LogoMark size={26} />
          <span className="text-[15px] font-extrabold tracking-tight">AI Notebook</span>
        </span>
        <span className="text-[11px] text-ink-faint">Exported {exported}</span>
      </header>

      <h1 className="mt-6 text-[24px] font-extrabold leading-tight">{title}</h1>
      <p className="mt-1.5 text-[12px] text-ink-muted">{[noteTypeLabel(type), ...meta].filter(Boolean).join("  ·  ")}</p>
      {topics.length > 0 && <p className="mt-2 text-[11px] font-semibold text-primary">{topics.join("  ·  ")}</p>}

      {a?.summary && (
        <Block title="Summary">
          <p className="text-[13px] leading-relaxed">{a.summary}</p>
          {a.howItWent && <p className="mt-2 text-[12.5px] leading-relaxed text-ink-muted">{a.howItWent}</p>}
        </Block>
      )}
      {raw.note_body && <Block title="Note"><p className="whitespace-pre-wrap text-[13px] leading-relaxed">{raw.note_body}</p></Block>}
      {highlights.length > 0 && <Block title="Highlights"><Bullets items={highlights.map((text) => ({ text }))} /></Block>}
      {raw.reflection && <Block title="Reflection"><p className="text-[13px] italic leading-relaxed">{raw.reflection}</p></Block>}

      {studyNotes.length > 0 && (
        <Block title="Study notes">
          <div className="flex flex-col gap-3">
            {studyNotes.map((s, i) => (
              <div key={i} className="print-avoid">
                <h3 className="text-[13px] font-bold">{s.heading}</h3>
                <Bullets items={(s.points ?? []).map((text) => ({ text }))} />
              </div>
            ))}
          </div>
        </Block>
      )}
      {terms.length > 0 && (
        <Block title="Key terms">
          <dl className="grid grid-cols-2 gap-x-6 gap-y-2 text-[12px]">
            {terms.map((x, i) => (
              <div key={i} className="print-avoid">
                <dt className="font-bold">{x.term}</dt>
                <dd className="leading-relaxed text-ink-muted">{x.definition}</dd>
              </div>
            ))}
          </dl>
        </Block>
      )}

      {a?.keyPoints.length > 0 && <Block title="Key points"><Bullets items={a.keyPoints} /></Block>}
      {a?.decisions.length > 0 && <Block title="Decisions"><Bullets items={a.decisions} /></Block>}

      {a?.actionItems.length > 0 && (
        <Block title="Action items">
          <table className="w-full border-collapse text-left text-[12px]">
            <thead>
              <tr className="border-b border-line text-[11px] text-ink-faint">
                <th className="py-1.5 pr-3 font-semibold">Task</th>
                <th className="py-1.5 pr-3 font-semibold">Owner</th>
                <th className="py-1.5 pr-3 font-semibold">Due</th>
                <th className="py-1.5 font-semibold">Priority</th>
              </tr>
            </thead>
            <tbody>
              {a.actionItems.map((x, i) => (
                <tr key={i} className="print-avoid border-b border-line/70 align-top">
                  <td className="py-1.5 pr-3 font-semibold">{x.task}</td>
                  <td className="py-1.5 pr-3">{who(x.owner) || "-"}</td>
                  <td className="py-1.5 pr-3">{x.due || "-"}</td>
                  <td className="py-1.5">{LEVEL[x.priority] ?? "-"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Block>
      )}

      {a?.questions.length > 0 && (
        <Block title="Questions">
          <ul className="flex flex-col gap-2 text-[12.5px] leading-relaxed">
            {a.questions.map((q, i) => (
              <li key={i} className="print-avoid">
                <b className="font-semibold">{q.question}</b>
                {q.answer && <span className="block text-ink-muted">{q.answer}</span>}
              </li>
            ))}
          </ul>
        </Block>
      )}

      {a?.risks.length > 0 && <Block title="Risks"><Bullets items={a.risks.map((x) => ({ text: typeof x === "string" ? x : x?.text }))} /></Block>}

      {(a?.participants.length > 0 || talkTime?.length > 0) && (
        <Block title="Who spoke">
          <ul className="grid grid-cols-2 gap-x-6 gap-y-1.5 text-[12px]">
            {(talkTime?.length ? talkTime : a.participants.flatMap((p) => p.speakers ?? []).map((speaker) => ({ speaker }))).map((x) => (
              <li key={x.speaker} className="flex justify-between gap-3">
                <span className="font-semibold">{who(x.speaker)}</span>
                {x.seconds > 0 && <span className="text-ink-muted">{spoken(x.seconds)}</span>}
              </li>
            ))}
          </ul>
        </Block>
      )}

      {a?.nextMeeting && <Block title="Next meeting"><p className="text-[12.5px] leading-relaxed">{a.nextMeeting}</p></Block>}
      {a?.conclusion && <Block title="Conclusion"><p className="text-[12.5px] leading-relaxed">{a.conclusion}</p></Block>}

      {cards.length > 0 && (
        <Block title="Flashcards">
          <ol className="flex flex-col gap-2 pl-4 text-[12px] leading-relaxed">
            {cards.map((c, i) => (
              <li key={i} className="print-avoid list-decimal">
                <b className="font-semibold">{c.front}</b>
                <span className="block text-ink-muted">{c.back}</span>
              </li>
            ))}
          </ol>
        </Block>
      )}

      <footer className="mt-10 border-t border-line pt-2 text-[10px] text-ink-faint">Written by AI Notebook from the recording. Check important details against the audio.</footer>
    </article>
  );
}
