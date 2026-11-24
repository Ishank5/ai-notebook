import { Check, ChevronLeft, ChevronRight, RotateCcw, Shuffle, Trophy, X } from "lucide-react";
import { useEffect, useState } from "react";

import { Btn, cn } from "../../components/ui";

/* A lecture's study material: notes and key terms, flip cards, and a short quiz. */

/** Study notes by section, then the key terms as a glossary. */
export function StudyNotes({ notes, terms }) {
  const sections = (notes ?? []).filter((s) => s?.heading || s?.points?.length);
  const glossary = (terms ?? []).filter((x) => x?.term);
  if (!sections.length && !glossary.length) {
    return <p className="card card-pad text-sm text-ink-muted">No study notes for this lecture.</p>;
  }
  return (
    <div className="flex flex-col gap-4">
      {sections.length > 0 && (
        <section className="card card-pad flex flex-col gap-6">
          {sections.map((s, i) => (
            <div key={i} className="flex flex-col gap-2.5">
              <h2 className="flex items-baseline gap-2.5">
                <span className="num text-xs font-bold text-primary">{String(i + 1).padStart(2, "0")}</span>
                {s.heading}
              </h2>
              <ul className="flex flex-col gap-2 pl-6">
                {(s.points ?? []).map((p, j) => (
                  <li key={j} className="relative text-sm leading-relaxed text-ink before:absolute before:-left-4 before:top-2 before:size-1.5 before:rounded-full before:bg-primary/60">
                    {p}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </section>
      )}
      {glossary.length > 0 && (
        <section className="card card-pad flex flex-col gap-4">
          <h2 className="flex items-center gap-2">Key terms<span className="num text-xs font-semibold text-ink-faint">{glossary.length}</span></h2>
          <dl className="grid gap-2 sm:grid-cols-2">
            {glossary.map((g, i) => (
              <div key={i} className="rounded-2xl bg-bg px-4 py-3">
                <dt className="text-sm font-bold">{g.term}</dt>
                <dd className="mt-1 text-[13px] leading-relaxed text-ink-muted">{g.definition}</dd>
              </div>
            ))}
          </dl>
        </section>
      )}
    </div>
  );
}

const typing = (el) => el && (["INPUT", "TEXTAREA", "SELECT"].includes(el.tagName) || el.isContentEditable);

/** One card at a time: click or Space to turn it over, arrows to move, Shuffle to mix the deck. */
export function Flashcards({ cards }) {
  const deck = (cards ?? []).filter((c) => c?.front);
  const [order, setOrder] = useState(() => deck.map((_, i) => i));
  const [pos, setPos] = useState(0);
  const [flipped, setFlipped] = useState(false);
  const [seen, setSeen] = useState(() => new Set());

  const go = (next) => {
    setFlipped(false);
    setPos(Math.max(0, Math.min(deck.length - 1, next)));
  };
  const flip = () => {
    setFlipped((f) => !f);
    setSeen((s) => new Set(s).add(order[pos]));
  };

  useEffect(() => {
    const onKey = (e) => {
      if (typing(e.target) || e.metaKey || e.ctrlKey || e.altKey) return;
      if (e.key === "ArrowRight") { e.preventDefault(); go(pos + 1); }
      else if (e.key === "ArrowLeft") { e.preventDefault(); go(pos - 1); }
      else if ((e.key === " " || e.key === "Enter") && e.target?.tagName !== "BUTTON") { e.preventDefault(); flip(); }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  if (!deck.length) return <p className="card card-pad text-sm text-ink-muted">No flashcards for this lecture.</p>;
  const card = deck[order[pos]] ?? deck[0];

  return (
    <section className="flex flex-col gap-4">
      <div className="flex items-center justify-between gap-3">
        <p className="num text-sm font-semibold text-ink-muted">
          Card <b className="text-ink">{pos + 1}</b> of {deck.length}
          <span className="ml-2 text-xs text-ink-faint">{seen.size} turned</span>
        </p>
        <Btn variant="ghost" size="sm" onClick={() => { setOrder(shuffle(order)); setPos(0); setFlipped(false); }}>
          <Shuffle className="size-4" aria-hidden="true" />
          Shuffle
        </Btn>
      </div>

      <button
        type="button"
        onClick={flip}
        aria-label={flipped ? "Show the question" : "Show the answer"}
        aria-live="polite"
        className="flip block h-72 w-full rounded-card text-left sm:h-80"
      >
        <div className="flip-inner" data-flipped={flipped}>
          <div className="flip-face card flex flex-col p-6 sm:p-8">
            <span className="label">Question</span>
            <span className="m-auto max-w-xl text-center text-lg font-bold leading-snug text-ink sm:text-[22px]">{card.front}</span>
            <span className="text-center text-xs font-semibold text-ink-faint">Tap to turn over</span>
          </div>
          <div className="flip-face flip-back flex flex-col rounded-card border border-primary/20 bg-primary-tint p-6 sm:p-8">
            <span className="label text-primary">Answer</span>
            <span className="m-auto max-w-xl overflow-y-auto text-center text-base font-semibold leading-relaxed text-ink sm:text-lg">{card.back}</span>
            <span className="text-center text-xs font-semibold text-primary/70">Tap to turn back</span>
          </div>
        </div>
      </button>

      <div className="flex items-center justify-between gap-3">
        <Btn variant="outline" onClick={() => go(pos - 1)} disabled={pos === 0} aria-label="Previous card">
          <ChevronLeft className="size-4" aria-hidden="true" />
          <span className="max-sm:hidden">Previous</span>
        </Btn>
        <div className="flex min-w-0 flex-1 justify-center gap-1.5" aria-hidden="true">
          {deck.map((_, i) => (
            <span key={i} className={cn("h-1.5 rounded-full transition-all", i === pos ? "w-5 bg-primary" : seen.has(order[i]) ? "w-1.5 bg-primary/40" : "w-1.5 bg-line")} />
          ))}
        </div>
        <Btn variant="outline" onClick={() => go(pos + 1)} disabled={pos === deck.length - 1} aria-label="Next card">
          <span className="max-sm:hidden">Next</span>
          <ChevronRight className="size-4" aria-hidden="true" />
        </Btn>
      </div>
      <p className="text-center text-xs text-ink-faint max-sm:hidden">Space turns the card, arrow keys move between cards.</p>
    </section>
  );
}

function shuffle(list) {
  const a = [...list];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

const LETTERS = ["A", "B", "C", "D"];

/** Pick an answer for each question, then check: right and wrong marked, with why, and a score. */
export function Quiz({ questions }) {
  const qs = (questions ?? []).filter((q) => q?.question && Array.isArray(q.options) && q.options.length >= 2);
  const [answers, setAnswers] = useState({});
  const [revealed, setRevealed] = useState(false);

  if (!qs.length) return <p className="card card-pad text-sm text-ink-muted">No quiz for this lecture.</p>;
  const answered = Object.keys(answers).length;
  const score = qs.reduce((s, q, i) => s + (answers[i] === q.answer_index ? 1 : 0), 0);

  return (
    <section className="flex flex-col gap-4">
      {revealed && (
        <div className="card card-pad flex items-center gap-4">
          <span className={cn("flex size-12 shrink-0 items-center justify-center rounded-2xl", score === qs.length ? "bg-success-tint text-success" : "bg-primary-tint text-primary")}>
            <Trophy className="size-6" aria-hidden="true" />
          </span>
          <div className="min-w-0 flex-1">
            <p className="text-2xl font-extrabold tracking-tight">{score} of {qs.length}</p>
            <p className="text-sm text-ink-muted">{score === qs.length ? "Perfect score." : score >= qs.length / 2 ? "Good work. Check the ones you missed." : "Worth another look at the study notes."}</p>
          </div>
          <Btn variant="outline" size="sm" onClick={() => { setAnswers({}); setRevealed(false); }}>
            <RotateCcw className="size-4" aria-hidden="true" />
            Try again
          </Btn>
        </div>
      )}

      {qs.map((q, i) => {
        const chosen = answers[i];
        return (
          <fieldset key={i} className="card card-pad flex flex-col gap-3">
            <legend className="sr-only">Question {i + 1}</legend>
            <p className="flex gap-3 text-[15px] font-bold leading-snug">
              <span className="num shrink-0 text-primary">{i + 1}.</span>
              <span>{q.question}</span>
            </p>
            <div role="radiogroup" aria-label={`Answers to question ${i + 1}`} className="grid gap-2 sm:grid-cols-2">
              {q.options.slice(0, 4).map((opt, k) => {
                const isChosen = chosen === k;
                const isRight = q.answer_index === k;
                const state = !revealed ? (isChosen ? "chosen" : "idle") : isRight ? "right" : isChosen ? "wrong" : "muted";
                return (
                  <button
                    key={k}
                    type="button"
                    role="radio"
                    aria-checked={isChosen}
                    disabled={revealed}
                    onClick={() => setAnswers((a) => ({ ...a, [i]: k }))}
                    className={cn(
                      "flex items-start gap-3 rounded-2xl border px-3.5 py-3 text-left text-sm leading-snug transition-colors",
                      state === "idle" && "border-line bg-surface hover:border-primary/50 hover:bg-bg",
                      state === "chosen" && "border-primary bg-primary-tint text-ink",
                      state === "right" && "border-success/40 bg-success-tint text-ink",
                      state === "wrong" && "border-record/40 bg-record-tint text-ink",
                      state === "muted" && "border-line bg-surface text-ink-faint",
                    )}
                  >
                    <span className={cn(
                      "flex size-6 shrink-0 items-center justify-center rounded-full text-[11px] font-bold",
                      state === "chosen" ? "bg-primary text-white" : state === "right" ? "bg-success text-white" : state === "wrong" ? "bg-record text-white" : "bg-surface-alt text-ink-muted",
                    )}>
                      {state === "right" ? <Check className="size-3.5" strokeWidth={3} aria-hidden="true" /> : state === "wrong" ? <X className="size-3.5" strokeWidth={3} aria-hidden="true" /> : LETTERS[k]}
                    </span>
                    <span className="pt-0.5">{opt}</span>
                  </button>
                );
              })}
            </div>
            {revealed && q.explanation && (
              <p className={cn("rounded-2xl px-4 py-3 text-[13px] leading-relaxed", chosen === q.answer_index ? "bg-success-tint/60 text-ink" : "bg-bg text-ink")}>
                <b className="font-bold">{chosen === q.answer_index ? "Correct. " : chosen === undefined ? "Not answered. " : "Not quite. "}</b>
                {q.explanation}
              </p>
            )}
          </fieldset>
        );
      })}

      {!revealed && (
        <div className="flex flex-col items-center gap-2 pt-1 sm:flex-row sm:justify-end">
          <span className="num text-xs text-ink-faint">{answered} of {qs.length} answered</span>
          <Btn onClick={() => setRevealed(true)} disabled={answered === 0} className="max-sm:w-full">
            <Check className="size-4" aria-hidden="true" />
            Check answers
          </Btn>
        </div>
      )}
    </section>
  );
}
