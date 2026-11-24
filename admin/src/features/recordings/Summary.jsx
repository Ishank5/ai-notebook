import { Play } from "lucide-react";

import { Chip, cn } from "../../components/ui";
import { clock } from "./format";
import { t } from "./strings";

const MOOD_TONE = { very_positive: "success", positive: "success", neutral: "neutral", mixed: "warning", tense: "warning", negative: "danger" };
const LEVEL_TONE = { high: "danger", medium: "warning", low: "neutral" };
const QUESTION_TONE = { answered: "success", open: "warning", parked: "neutral" };

/*
 * The summary comes with snake_case keys (how_it_went, key_points, at_seconds ...); the brief
 * writes the same fields in camelCase. Both spellings are read, so either works.
 */
const val = (o, camel, snake = camel) => o?.[camel] ?? o?.[snake];
const list = (o, camel, snake) => {
  const v = val(o, camel, snake);
  return Array.isArray(v) ? v : [];
};
const at = (i) => val(i, "atSeconds", "at_seconds");
const raisedBy = (i) => val(i, "raisedBy", "raised_by");

/** One shape for the screens, whichever spelling the summary used. */
// eslint-disable-next-line react-refresh/only-export-components
export function readAnalysis(a) {
  if (!a) return null;
  return {
    title: a.title,
    summary: a.summary,
    howItWent: val(a, "howItWent", "how_it_went"),
    mood: a.mood,
    moodReason: val(a, "moodReason", "mood_reason"),
    meetingKind: val(a, "meetingKind", "meeting_kind"),
    keyPoints: list(a, "keyPoints", "key_points").map((i) => ({ text: i.text, at: at(i) })),
    decisions: list(a, "decisions").map((i) => ({ text: i.text, at: at(i) })),
    actionItems: list(a, "actionItems", "action_items").map((i) => ({ ...i, raisedBy: raisedBy(i), at: at(i) })),
    questions: list(a, "questions", "questions_raised").map((i) => ({ ...i, raisedBy: raisedBy(i), at: at(i) })),
    issues: list(a, "issues", "issues_raised").map((i) => ({ ...i, raisedBy: raisedBy(i), at: at(i) })),
    commitments: list(a, "commitments").map((i) => ({ ...i, at: at(i) })),
    participants: list(a, "participants"),
    opportunities: list(a, "opportunities"),
    risks: list(a, "risks"),
    nextMeeting: val(a, "nextMeeting", "next_meeting"),
    futureScope: val(a, "futureScope", "future_scope"),
    conclusion: a.conclusion,
  };
}

/** "team_review" → "Team review". */
// eslint-disable-next-line react-refresh/only-export-components
export const humanize = (s) => {
  const text = String(s ?? "").replace(/[_-]+/g, " ").trim();
  return text ? text[0].toUpperCase() + text.slice(1) : "";
};

/**
 * A meeting's summary as section cards. Every item said at a moment carries its time, and clicking
 * it plays the meeting from there. Empty sections are left out. `flat` drops the cards (for use
 * inside another card), `showTitle` shows the summary's own title.
 */
export function SummaryView({ analysis, names, onSeek, flat = false, showTitle = false }) {
  const a = readAnalysis(analysis);
  if (!a) return null;
  const who = (label) => (label && names?.get(label) ? `${names.get(label)} (${label})` : label);

  return (
    <div className={cn("flex flex-col", flat ? "gap-6" : "gap-4")}>
      <Section flat={flat} title={t("section.summary")}>
        {showTitle && a.title && <p className="text-base font-bold">{a.title}</p>}
        {(a.mood || a.meetingKind) && (
          <div className="flex flex-wrap items-center gap-2">
            {a.mood && <Chip tone={MOOD_TONE[a.mood] ?? "neutral"}>{t(`mood.${a.mood}`, { defaultValue: humanize(a.mood) })}</Chip>}
            {a.meetingKind && <Chip tone="primary">{humanize(a.meetingKind)}</Chip>}
          </div>
        )}
        {a.summary && <p className="whitespace-pre-wrap text-[15px] leading-relaxed">{a.summary}</p>}
        {a.howItWent && (
          <div className="flex flex-col gap-1">
            <h3 className="label">{t("section.howItWent")}</h3>
            <p className="whitespace-pre-wrap leading-relaxed">{a.howItWent}</p>
          </div>
        )}
        {a.moodReason && (
          <div className="flex flex-col gap-1">
            <h3 className="label">{t("section.moodReason")}</h3>
            <p className="whitespace-pre-wrap text-ink-muted">{a.moodReason}</p>
          </div>
        )}
      </Section>

      <Items flat={flat} title={t("section.keyPoints")} items={a.keyPoints} onSeek={onSeek} render={(i) => i.text} />
      <Items flat={flat} title={t("section.decisions")} items={a.decisions} onSeek={onSeek} render={(i) => i.text} />

      <Items flat={flat} title={t("section.actionItems")} items={a.actionItems} onSeek={onSeek}
        render={(i) => (
          <div className="flex flex-col gap-1.5">
            <span className="font-semibold">{i.task}</span>
            <Meta>
              {i.priority && <Chip tone={LEVEL_TONE[i.priority] ?? "neutral"} size="sm">{t(`level.${i.priority}`, { defaultValue: humanize(i.priority) })}</Chip>}
              {i.owner && <span>{t("field.owner")}: <b className="font-semibold text-ink">{who(i.owner)}</b></span>}
              {i.due && <span>{t("field.due")}: {i.due}</span>}
              {i.raisedBy && <span>{t("field.raisedBy")}: {who(i.raisedBy)}</span>}
            </Meta>
          </div>
        )} />

      <Items flat={flat} title={t("section.questions")} items={a.questions} onSeek={onSeek}
        render={(i) => (
          <div className="flex flex-col gap-1.5">
            <span className="font-semibold">{i.question}</span>
            {i.answer && <span className="text-ink-muted">{i.answer}</span>}
            <Meta>
              {i.status && <Chip tone={QUESTION_TONE[i.status] ?? "neutral"} size="sm">{t(`question.${i.status}`, { defaultValue: humanize(i.status) })}</Chip>}
              {i.raisedBy && <span>{t("field.raisedBy")}: {who(i.raisedBy)}</span>}
            </Meta>
          </div>
        )} />

      <Items flat={flat} title={t("section.issues")} items={a.issues} onSeek={onSeek}
        render={(i) => (
          <div className="flex flex-col gap-1.5">
            <span className="font-semibold">{i.topic}</span>
            {i.detail && <span className="text-ink-muted">{i.detail}</span>}
            <Meta>
              {i.severity && <Chip tone={LEVEL_TONE[i.severity] ?? "neutral"} size="sm">{t(`level.${i.severity}`, { defaultValue: humanize(i.severity) })}</Chip>}
              {i.area && <span>{i.area}</span>}
              {i.raisedBy && <span>{t("field.raisedBy")}: {who(i.raisedBy)}</span>}
            </Meta>
          </div>
        )} />

      <Items flat={flat} title={t("section.commitments")} items={a.commitments} onSeek={onSeek}
        render={(i) => (
          <span>{i.text}{i.by && <span className="text-ink-muted"> · {who(i.by)}</span>}</span>
        )} />

      {a.participants.length > 0 && (
        <Section flat={flat} title={t("section.participants")} count={a.participants.length}>
          <ul className="grid gap-2 sm:grid-cols-2">
            {a.participants.map((p, n) => (
              <li key={n} className="rounded-2xl bg-surface-alt px-4 py-3">
                <div className="flex flex-wrap items-baseline gap-x-2">
                  <span className="font-semibold">{p.name || t("field.unnamedSpeaker")}</span>
                  {p.role && <span className="text-xs text-ink-muted">{p.role}</span>}
                </div>
                {p.stance && <p className="mt-1 text-[13px]">{p.stance}</p>}
                {p.note && <p className="mt-0.5 text-[13px] text-ink-muted">{p.note}</p>}
              </li>
            ))}
          </ul>
        </Section>
      )}

      <Bullets flat={flat} title={t("section.opportunities")} items={a.opportunities} />
      <Bullets flat={flat} title={t("section.risks")} items={a.risks} />
      <Prose flat={flat} title={t("section.nextMeeting")} text={a.nextMeeting} />
      <Prose flat={flat} title={t("section.futureScope")} text={a.futureScope} />
      <Prose flat={flat} title={t("section.conclusion")} text={a.conclusion} />
    </div>
  );
}

function Section({ title, count, flat, children }) {
  return (
    <section className={cn("flex flex-col gap-3 text-sm text-ink", !flat && "card card-pad")}>
      <h2 className="flex items-center gap-2">
        {title}
        {count > 0 && <span className="num text-xs font-semibold text-ink-faint">{count}</span>}
      </h2>
      {children}
    </section>
  );
}

function Meta({ children }) {
  return <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-ink-muted">{children}</div>;
}

function Prose({ flat, title, text }) {
  if (!text) return null;
  return <Section flat={flat} title={title}><p className="whitespace-pre-wrap leading-relaxed">{text}</p></Section>;
}

function Bullets({ flat, title, items }) {
  const texts = (items ?? []).map((i) => (typeof i === "string" ? i : i?.text)).filter(Boolean);
  if (!texts.length) return null;
  return (
    <Section flat={flat} title={title} count={texts.length}>
      <ul className="flex flex-col gap-2">
        {texts.map((text, n) => (
          <li key={n} className="flex gap-3 leading-relaxed">
            <span className="mt-2 size-1.5 shrink-0 rounded-full bg-primary" aria-hidden="true" />
            <span>{text}</span>
          </li>
        ))}
      </ul>
    </Section>
  );
}

function Items({ flat, title, items, render, onSeek }) {
  if (!items?.length) return null;
  return (
    <Section flat={flat} title={title} count={items.length}>
      <ul className="flex flex-col divide-y divide-line">
        {items.map((item, n) => (
          <li key={n} className="flex items-start gap-3 py-3 leading-relaxed first:pt-0 last:pb-0">
            <div className="min-w-0 flex-1">{render(item)}</div>
            <Moment seconds={item.at} onSeek={onSeek} />
          </li>
        ))}
      </ul>
    </Section>
  );
}

/** The time an item was said, as a button that plays from there. */
function Moment({ seconds, onSeek }) {
  if (!Number.isFinite(seconds) || seconds < 0) return null;
  return (
    <button
      type="button"
      onClick={() => onSeek?.(seconds)}
      disabled={!onSeek}
      title={t("transcript.playFrom")}
      className="num inline-flex h-7 shrink-0 items-center gap-1 rounded-full bg-primary-tint px-2.5 text-[11px] font-bold text-primary transition-colors hover:bg-primary hover:text-white disabled:bg-surface-alt disabled:text-ink-faint"
    >
      {onSeek && <Play className="size-3" aria-hidden="true" />}
      {clock(seconds)}
    </button>
  );
}
