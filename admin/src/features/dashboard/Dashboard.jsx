import {
  ArrowRight,
  CalendarClock,
  CircleAlert,
  Clock,
  ListChecks,
  MessagesSquare,
  Mic,
  NotebookPen,
  Smile,
  Sparkles,
  Tag,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";

import { BarList, ColumnChart, MoodStrip, Sparkline } from "../../components/charts";
import { LogoMark } from "../../components/Logo";
import { NOTE_TYPES, TypeBadge, TypeIcon, noteTypeOf } from "../../components/NoteType";
import { CardHead, Chip, ErrorState, Segmented, Skeleton, cn } from "../../components/ui";
import { useAuth } from "../../lib/auth";
import { dayKey, dueLabel, greeting, longToday, shortDateTime, shortDay, weekdayDay } from "../../lib/dates";
import { poll } from "../../lib/poll";
import { duration, statusOf, titleOf } from "../recordings/format";
import { t } from "../recordings/strings";
import { SourceLink, TaskRow } from "../tasks/TaskRow";
import { sortTasks, useTasks } from "../tasks/useTasks";
import { DASHBOARD_PATH } from "./api";
import { DigestCard } from "./DigestCard";

const DAYS = 30;
const NUM = new Intl.NumberFormat("en-IN", { numberingSystem: "latn" });
const n = (v) => NUM.format(Number.isFinite(v) ? v : 0);

/** Hours with one decimal under ten, whole above: 0.8, 6.5, 14. */
const hours = (minutes) => {
  const h = (minutes ?? 0) / 60;
  return h < 10 ? (Math.round(h * 10) / 10).toString() : n(Math.round(h));
};

/**
 * The first page: how much was recorded, what the AI made of it, and what is waiting to be done.
 * A brand new account gets a short welcome instead of empty charts.
 */
export default function Dashboard() {
  const { profile } = useAuth();
  const [state, setState] = useState({ status: "loading", data: null });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => poll(
    DASHBOARD_PATH(DAYS),
    (data) => setState({ status: "ready", data }),
    () => setState({ status: "error", data: null }),
    60000,
  ), [attempt]);

  const first = (profile?.name || "").trim().split(/\s+/)[0];
  const d = state.data;
  const isNew = d && (d.recent?.length ?? 0) === 0 && (d.totals?.notes ?? 0) === 0;

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="min-w-0">
          <p className="text-sm font-semibold text-ink-faint">{longToday()}</p>
          <h1 className="mt-1">{greeting()}{first ? `, ${first}` : ""}</h1>
        </div>
        {!isNew && (
          <Link to="/ask" className="btn-secondary">
            <MessagesSquare className="size-4" aria-hidden="true" />
            Ask your notes
          </Link>
        )}
      </div>

      {state.status === "loading" && <DashboardSkeleton />}
      {state.status === "error" && (
        <ErrorState title="Could not load the dashboard" body="Check the connection and try again." onRetry={() => setAttempt((x) => x + 1)} />
      )}
      {state.status === "ready" && (isNew ? <FirstRun /> : <Board data={d} />)}
    </div>
  );
}

function Board({ data }) {
  const navigate = useNavigate();
  const totals = data.totals ?? {};
  const byDay = useMemo(() => data.byDay ?? [], [data.byDay]);
  const last14 = byDay.slice(-14);

  // Titles for links out of the digest: every ready note in the window, plus the latest few.
  const titles = useMemo(() => {
    const m = new Map();
    for (const x of data.moods ?? []) if (x.recordingId && x.title) m.set(x.recordingId, x.title);
    for (const r of data.recent ?? []) m.set(r.id, titleOf(r) || m.get(r.id));
    return m;
  }, [data.moods, data.recent]);

  return (
    <div className="flex flex-col gap-4 lg:gap-5">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4 lg:gap-5">
        <Tile to="/recordings" icon={NotebookPen} label="Notes this week" value={n(totals.notesThisWeek)}
          sub={`${n(totals.notes)} in ${DAYS} days`} spark={last14.map((x) => x.notes)} />
        <Tile to="/recordings?range=month" icon={Clock} label="Hours recorded" value={hours(totals.minutes)}
          sub={`${n(totals.minutesThisWeek)} min this week`} spark={last14.map((x) => x.minutes)} />
        <Tile to="/tasks" icon={ListChecks} label="Open tasks" value={n(totals.openTasks)} sub={`${n(totals.doneTasks)} done`} />
        <Tile to="/tasks" icon={CircleAlert} label="Overdue" value={n(totals.overdueTasks)}
          sub={totals.overdueTasks > 0 ? "Need attention" : "All on track"} alert={totals.overdueTasks > 0} />
      </div>

      {/* Two columns that flow on their own on wide screens; on phones the cards interleave by order. */}
      <div className="flex flex-col gap-4 lg:grid lg:grid-cols-3 lg:items-start lg:gap-5">
        <div className="flex min-w-0 flex-col gap-4 max-lg:contents lg:col-span-2 lg:gap-5">
          <div className="min-w-0 max-lg:order-1"><DigestCard titles={titles} /></div>
          <div className="min-w-0 max-lg:order-4"><Activity byDay={byDay} /></div>
          <div className="min-w-0 max-lg:order-6">
            <MoodCard moods={data.moods ?? []} days={byDay.map((x) => x.date)} onOpen={(id) => navigate(`/recordings/${id}`)} />
          </div>
          <div className="min-w-0 max-lg:order-7"><RecentNotes rows={data.recent ?? []} /></div>
        </div>
        <div className="flex min-w-0 flex-col gap-4 max-lg:contents lg:gap-5">
          <div className="min-w-0 max-lg:order-2"><OpenTasks /></div>
          <div className="min-w-0 max-lg:order-3"><Upcoming tasks={data.upcoming ?? []} /></div>
          <div className="min-w-0 max-lg:order-5"><NoteTypes byType={data.byType ?? {}} /></div>
          <div className="min-w-0 max-lg:order-8"><Topics topics={data.topTopics ?? []} /></div>
        </div>
      </div>
    </div>
  );
}

function Tile({ to, icon: Icon, label, value, sub, spark, alert }) {
  return (
    <Link to={to} className="card group flex min-w-0 flex-col gap-3 p-4 transition-colors hover:border-primary/40 sm:p-5">
      <div className="flex items-center gap-2 text-[13px] font-semibold text-ink-muted">
        <Icon className={cn("size-4 shrink-0", alert ? "text-record" : "text-primary")} aria-hidden="true" />
        <span className="truncate">{label}</span>
      </div>
      <div className="flex items-end justify-between gap-2">
        <div className="min-w-0">
          <div className="text-[28px] font-extrabold leading-none tracking-tight text-ink sm:text-[32px]">{value}</div>
          <div className={cn("mt-2 truncate text-xs", alert ? "font-semibold text-record" : "text-ink-faint")}>{sub}</div>
        </div>
        {spark && <span className="max-sm:hidden"><Sparkline values={spark} label={`${label}, last 14 days`} width={76} /></span>}
      </div>
    </Link>
  );
}

function Activity({ byDay }) {
  const [measure, setMeasure] = useState("notes");
  const empty = byDay.every((d) => !d.notes && !d.minutes);
  const chartData = byDay.map((d) => {
    const v = measure === "notes" ? d.notes : d.minutes;
    return {
      key: d.date,
      label: shortDay(d.date),
      value: v,
      detail: `${weekdayDay(d.date)}${measure === "notes" ? ` · ${n(d.minutes)} min` : ` · ${d.notes === 1 ? "1 note" : `${n(d.notes)} notes`}`}`,
      tip: measure === "notes" ? (v === 1 ? "1 note" : `${n(v)} notes`) : `${n(v)} min`,
    };
  });
  return (
    <section className="card card-pad flex h-full flex-col gap-4">
      <CardHead
        title="Activity"
        action={
          <Segmented size="sm" aria-label="Measure" value={measure} onChange={setMeasure}
            options={[{ value: "notes", label: "Notes" }, { value: "minutes", label: "Minutes" }]} />
        }
      />
      <p className="-mt-3 text-xs text-ink-faint">{measure === "notes" ? "Notes per day" : "Minutes recorded per day"}, last {DAYS} days</p>
      {empty ? (
        <Quiet icon={Mic} text={`No notes in the last ${DAYS} days.`} />
      ) : (
        <ColumnChart data={chartData} format={n} ariaLabel={`${measure === "notes" ? "Notes" : "Minutes"} per day, last ${DAYS} days`} />
      )}
    </section>
  );
}

function NoteTypes({ byType }) {
  const items = NOTE_TYPES
    .map((nt) => ({ key: nt.id, label: nt.label, icon: nt.icon, value: byType[nt.id] ?? 0, to: `/recordings?type=${nt.id}&range=month` }))
    .sort((a, b) => b.value - a.value);
  const total = items.reduce((s, i) => s + i.value, 0);
  return (
    <section className="card card-pad flex flex-col gap-4">
      <CardHead title="Note types" count={total} />
      {total === 0 ? <Quiet icon={NotebookPen} text="Nothing yet." /> : <BarList items={items} total={total} />}
    </section>
  );
}

function MoodCard({ moods, days, onOpen }) {
  const counts = moods.reduce((m, x) => ({ ...m, [x.mood]: (m[x.mood] ?? 0) + 1 }), {});
  const top = Object.entries(counts).sort((a, b) => b[1] - a[1])[0];
  const label = { positive: "Mostly positive", neutral: "Mostly neutral", mixed: "Mostly mixed", negative: "Mostly negative" }[top?.[0]];
  return (
    <section className="card card-pad flex h-full flex-col gap-4">
      <CardHead title="Mood" action={label && <Chip tone={top[0] === "positive" ? "success" : top[0] === "negative" ? "danger" : top[0] === "mixed" ? "warning" : "neutral"} size="sm">{label}</Chip>} />
      <p className="-mt-3 text-xs text-ink-faint">Each dot is a note. Tap one to open it.</p>
      {moods.length === 0 || days.length === 0
        ? <Quiet icon={Smile} text="Moods show up once notes are summarised." />
        : <MoodStrip moods={moods} days={days} dayLabel={shortDay} onOpen={onOpen} />}
    </section>
  );
}

function OpenTasks() {
  const { status, tasks, toggle } = useTasks("open");
  const shown = sortTasks(tasks).slice(0, 5);
  return (
    <section className="card card-pad flex flex-col gap-2">
      <CardHead
        title="Open tasks"
        icon={ListChecks}
        count={status === "ready" ? tasks.filter((x) => !x.done).length : 0}
        action={<Link to="/tasks" className="inline-flex items-center gap-1 text-xs font-bold text-primary hover:text-primary-pressed">All tasks<ArrowRight className="size-3.5" aria-hidden="true" /></Link>}
      />
      {status === "loading" && <div className="flex flex-col gap-3 pt-2">{[0, 1, 2].map((i) => <Skeleton key={i} className="h-10" />)}</div>}
      {status === "error" && <Quiet text="Tasks could not load." />}
      {status === "ready" && shown.length === 0 && <Quiet icon={ListChecks} text="Nothing open. Nice." />}
      {status === "ready" && shown.length > 0 && (
        <ul className="flex flex-col divide-y divide-line">
          {shown.map((task) => <TaskRow key={task.id} task={task} onToggle={toggle} compact showOwner={false} />)}
        </ul>
      )}
    </section>
  );
}

function Upcoming({ tasks }) {
  const today = dayKey();
  return (
    <section className="card card-pad flex flex-col gap-3">
      <CardHead title="Deadlines" icon={CalendarClock} count={tasks.length} />
      {tasks.length === 0 ? (
        <Quiet icon={CalendarClock} text="Nothing due in the next 7 days." />
      ) : (
        <ul className="flex flex-col gap-1">
          {tasks.map((task) => {
            const late = task.dueDate && task.dueDate < today;
            const [, , day] = (task.dueDate ?? "").split("-");
            return (
              <li key={task.id} className="flex items-start gap-3 py-1.5">
                <span className={cn(
                  "flex w-11 shrink-0 flex-col items-center rounded-xl py-1 leading-none",
                  late ? "bg-record-tint text-record" : "bg-surface-alt text-ink",
                )}>
                  <span className="num text-[15px] font-extrabold">{Number(day) || "-"}</span>
                  <span className="mt-0.5 text-[10px] font-bold uppercase tracking-wide opacity-70">{task.dueDate ? shortDay(task.dueDate).split(" ")[1] : ""}</span>
                </span>
                <div className="min-w-0 flex-1">
                  <p className="line-clamp-2 text-[13px] font-semibold leading-snug">{task.text}</p>
                  <div className="mt-0.5 flex min-w-0 flex-wrap items-center gap-x-2 text-xs">
                    <span className={cn("shrink-0", late ? "font-semibold text-record" : "text-ink-muted")}>{dueLabel(task.dueDate, today)}</span>
                    <SourceLink task={task} className="max-w-full text-xs" />
                  </div>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

function RecentNotes({ rows }) {
  return (
    <section className="card flex h-full flex-col">
      <div className="px-5 pt-5 sm:px-6 sm:pt-6">
        <CardHead
          title="Recent notes"
          action={<Link to="/recordings" className="inline-flex items-center gap-1 text-xs font-bold text-primary hover:text-primary-pressed">All notes<ArrowRight className="size-3.5" aria-hidden="true" /></Link>}
        />
      </div>
      {rows.length === 0 ? (
        <div className="p-5"><Quiet icon={NotebookPen} text="No notes yet." /></div>
      ) : (
        <ul className="mt-2 flex flex-col divide-y divide-line pb-2">
          {rows.map((r) => {
            const st = statusOf(r);
            const type = noteTypeOf(r);
            return (
              <li key={r.id}>
                <Link to={`/recordings/${r.id}`} className="flex items-start gap-3 px-5 py-3 transition-colors hover:bg-bg sm:px-6">
                  <TypeIcon type={type} />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center justify-between gap-3">
                      <span className="min-w-0 truncate text-sm font-bold">{titleOf(r) || t("untitled")}</span>
                      {st.key !== "ready" && <Chip tone={st.tone} size="sm" dot={st.dot}>{t(`status.${st.key}`)}</Chip>}
                    </div>
                    {r.summaryText && <p className="mt-0.5 line-clamp-1 text-[13px] text-ink-muted">{r.summaryText}</p>}
                    <p className="num mt-1 flex flex-wrap items-center gap-x-2 text-xs text-ink-faint">
                      {type && <TypeBadge type={type} size="sm" className="!h-5 sm:hidden" />}
                      {r.meetingAt && <span>{shortDateTime(r.meetingAt)}</span>}
                      {r.durationMs > 0 && <span>· {duration(t, r.durationMs)}</span>}
                      {r.actionCount > 0 && <span>· {r.actionCount === 1 ? "1 task" : `${r.actionCount} tasks`}</span>}
                    </p>
                  </div>
                </Link>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

function Topics({ topics }) {
  const max = Math.max(1, ...topics.map((x) => x.count));
  return (
    <section className="card card-pad flex flex-col gap-4">
      <CardHead title="Recurring topics" icon={Tag} />
      {topics.length === 0 ? (
        <Quiet icon={Tag} text="Topics show up as notes come in." />
      ) : (
        <ul className="flex flex-wrap gap-2">
          {topics.slice(0, 10).map((x) => (
            <li key={x.topic}>
              <Link
                to={`/recordings?topic=${encodeURIComponent(x.topic)}&range=month`}
                className={cn(
                  "inline-flex h-8 items-center gap-1.5 rounded-full px-3 text-[13px] font-semibold transition-colors",
                  x.count / max > 0.6 ? "bg-primary text-white hover:bg-primary-pressed" : "bg-primary-tint text-primary hover:bg-chart-soft",
                )}
              >
                {x.topic}
                <span className="num text-[11px] opacity-75">{x.count}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function Quiet({ icon: Icon, text }) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-2 rounded-2xl bg-bg px-4 py-8 text-center">
      {Icon && <Icon className="size-5 text-ink-faint" aria-hidden="true" />}
      <p className="text-sm text-ink-muted">{text}</p>
    </div>
  );
}

function FirstRun() {
  const steps = [
    { icon: Mic, title: "Record", text: "Open the phone app and tap record." },
    { icon: Sparkles, title: "AI takes notes", text: "A summary, tasks and a mind map, in minutes." },
    { icon: ListChecks, title: "Act on it", text: "Tasks, insights and answers land here." },
  ];
  return (
    <section className="card flex flex-col items-center px-6 py-12 text-center sm:py-16">
      <LogoMark size={56} />
      <h2 className="mt-5 text-xl font-extrabold tracking-tight">Welcome to AI Notebook</h2>
      <p className="mt-2 max-w-md text-sm text-ink-muted">Your first note will show up here a few minutes after you record it.</p>
      <ol className="mt-8 grid w-full max-w-3xl gap-3 text-left sm:grid-cols-3">
        {steps.map((s, i) => (
          <li key={s.title} className="rounded-2xl bg-bg p-4">
            <div className="flex items-center gap-2">
              <span className="flex size-8 items-center justify-center rounded-full bg-primary-tint text-primary">
                <s.icon className="size-4" aria-hidden="true" />
              </span>
              <span className="num text-xs font-bold text-ink-faint">{i + 1}</span>
            </div>
            <div className="mt-3 text-sm font-bold">{s.title}</div>
            <div className="mt-1 text-[13px] text-ink-muted">{s.text}</div>
          </li>
        ))}
      </ol>
      <Link to="/tasks" className="btn-secondary mt-8">
        <ListChecks className="size-4" aria-hidden="true" />
        Add a task
      </Link>
    </section>
  );
}

function DashboardSkeleton() {
  return (
    <div className="flex flex-col gap-4 lg:gap-5" role="status" aria-label="Loading the dashboard">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4 lg:gap-5">
        {[0, 1, 2, 3].map((i) => (
          <div key={i} className="card flex flex-col gap-3 p-5">
            <Skeleton className="h-4 w-24" />
            <Skeleton className="h-8 w-16" />
            <Skeleton className="h-3 w-20" />
          </div>
        ))}
      </div>
      <div className="grid gap-4 lg:grid-cols-3 lg:gap-5">
        <div className="card flex flex-col gap-3 p-6 lg:col-span-2">
          <Skeleton className="h-5 w-32" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-5/6" />
          <Skeleton className="h-24 w-full rounded-2xl" />
        </div>
        <div className="card flex flex-col gap-3 p-6">
          <Skeleton className="h-5 w-28" />
          {[0, 1, 2].map((i) => <Skeleton key={i} className="h-10" />)}
        </div>
        <div className="card p-6 lg:col-span-2"><Skeleton className="h-48 w-full" /></div>
        <div className="card flex flex-col gap-4 p-6">{[0, 1, 2, 3].map((i) => <Skeleton key={i} className="h-7" />)}</div>
      </div>
    </div>
  );
}
