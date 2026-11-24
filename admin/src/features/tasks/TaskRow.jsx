import { CalendarClock, Check, NotebookPen, Pencil, Trash2, User } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router-dom";

import { Btn, Chip, Select, cn } from "../../components/ui";
import { dayKey, dueLabel } from "../../lib/dates";
import { clock } from "../recordings/format";
import { PRIORITIES } from "./useTasks";

const TONE = Object.fromEntries(PRIORITIES.map((p) => [p.id, p.tone]));
const LABEL = Object.fromEntries(PRIORITIES.map((p) => [p.id, p.label]));

/** The round tick at the start of a task. */
export function Tick({ done, onClick, label }) {
  return (
    <button
      type="button"
      role="checkbox"
      aria-checked={done}
      aria-label={label}
      onClick={onClick}
      className={cn(
        "mt-0.5 flex size-[22px] shrink-0 items-center justify-center rounded-full border-2 transition-colors",
        done ? "border-primary bg-primary text-white" : "border-ink-faint/60 text-transparent hover:border-primary hover:text-primary/50",
      )}
    >
      <Check className="size-3.5" strokeWidth={3} aria-hidden="true" />
    </button>
  );
}

/** Where an AI task came from: its note, at the moment it was said. */
export function SourceLink({ task, className }) {
  if (!task.recordingId) return null;
  const at = Number.isFinite(task.atSeconds) && task.atSeconds >= 0 ? task.atSeconds : null;
  const to = `/recordings/${task.recordingId}${at !== null ? `?t=${Math.floor(at)}` : ""}`;
  return (
    <Link to={to} className={cn("inline-flex min-w-0 items-center gap-1 font-semibold text-primary hover:text-primary-pressed", className)}>
      <NotebookPen className="size-3.5 shrink-0" aria-hidden="true" />
      <span className="truncate">{task.recordingTitle || "Open note"}</span>
      {at !== null && <span className="num shrink-0 font-bold">· {clock(at)}</span>}
    </Link>
  );
}

/**
 * One task: tick, text, and what matters about it (priority, due, owner, source). With onSave and
 * onDelete it can be edited in place and removed.
 */
export function TaskRow({ task, onToggle, onSave, onDelete, compact = false, showOwner = true }) {
  const [editing, setEditing] = useState(false);
  const today = dayKey();
  const overdue = !task.done && task.dueDate && task.dueDate < today;

  if (editing) {
    return (
      <li className="px-4 py-4 sm:px-5">
        <TaskForm
          initial={task}
          submitLabel="Save"
          onCancel={() => setEditing(false)}
          onSubmit={async (fields) => {
            const ok = await onSave(task, fields);
            if (ok) setEditing(false);
            return ok;
          }}
        />
      </li>
    );
  }

  return (
    <li className={cn("group flex items-start gap-3", compact ? "py-2.5" : "px-4 py-3.5 sm:px-5")}>
      <Tick done={task.done} onClick={() => onToggle(task)} label={task.done ? "Mark as open" : "Mark as done"} />
      <div className="min-w-0 flex-1">
        <p className={cn("break-words text-sm font-semibold leading-snug", task.done ? "text-ink-faint line-through" : "text-ink", compact && "line-clamp-2")}>
          {task.text}
        </p>
        <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-ink-muted">
          {task.priority && !task.done && (
            <Chip tone={TONE[task.priority] ?? "neutral"} size="sm">{LABEL[task.priority] ?? task.priority}</Chip>
          )}
          {(task.dueDate || task.due) && (
            <span className={cn("inline-flex items-center gap-1", overdue && "font-semibold text-record")}>
              <CalendarClock className="size-3.5" aria-hidden="true" />
              <span className="num">{task.dueDate ? dueLabel(task.dueDate, today) : task.due}</span>
            </span>
          )}
          {showOwner && task.owner && (
            <span className="inline-flex min-w-0 items-center gap-1">
              <User className="size-3.5 shrink-0" aria-hidden="true" />
              <span className="truncate">{task.owner}</span>
            </span>
          )}
          {!compact && <SourceLink task={task} className="max-w-full" />}
        </div>
      </div>
      {(onSave || onDelete) && (
        <div className="flex shrink-0 items-center gap-0.5 text-ink-faint">
          {onSave && (
            <button type="button" onClick={() => setEditing(true)} className="btn-ghost btn-sm w-8 px-0" aria-label="Edit task">
              <Pencil className="size-4" aria-hidden="true" />
            </button>
          )}
          {onDelete && (
            <button type="button" onClick={() => onDelete(task)} className="btn-ghost btn-sm w-8 px-0 hover:text-record" aria-label="Delete task">
              <Trash2 className="size-4" aria-hidden="true" />
            </button>
          )}
        </div>
      )}
    </li>
  );
}

/** Text, owner, date and priority, for adding a task or editing one. */
export function TaskForm({ initial, submitLabel, onSubmit, onCancel }) {
  const [form, setForm] = useState({
    text: initial?.text ?? "",
    owner: initial?.owner ?? "",
    dueDate: initial?.dueDate ?? "",
    priority: initial?.priority ?? "medium",
  });
  const [busy, setBusy] = useState(false);
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  async function submit(e) {
    e.preventDefault();
    if (!form.text.trim()) return;
    setBusy(true);
    await onSubmit({ text: form.text.trim(), owner: form.owner.trim(), dueDate: form.dueDate || null, priority: form.priority });
    setBusy(false);
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-3">
      <input
        className="field h-11 rounded-2xl"
        value={form.text}
        onChange={set("text")}
        placeholder="What needs doing?"
        aria-label="Task"
        autoFocus
        maxLength={300}
      />
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-[minmax(0,1fr)_170px_150px]">
        <input className="field" value={form.owner} onChange={set("owner")} placeholder="Owner" aria-label="Owner" maxLength={80} />
        <input className="field" type="date" value={form.dueDate} onChange={set("dueDate")} aria-label="Due date" />
        <Select
          aria-label="Priority"
          value={form.priority}
          onChange={(v) => setForm((f) => ({ ...f, priority: v }))}
          options={PRIORITIES.map((p) => ({ value: p.id, label: `${p.label} priority` }))}
          className="w-full [&_select]:h-10"
        />
      </div>
      <div className="flex justify-end gap-2">
        <Btn variant="ghost" size="sm" onClick={onCancel}>Cancel</Btn>
        <Btn type="submit" size="sm" loading={busy} disabled={!form.text.trim()}>{submitLabel}</Btn>
      </div>
    </form>
  );
}
