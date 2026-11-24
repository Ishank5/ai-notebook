import { useEffect, useRef, useState } from "react";
import { toast } from "sonner";

import { friendlyError } from "../../lib/api";
import { addDays, dayKey } from "../../lib/dates";
import { poll, refreshAll } from "../../lib/poll";
import { TASKS_PATH, createTask, deleteTask, updateTask } from "./api";

export const PRIORITIES = [
  { id: "high", label: "High", tone: "danger" },
  { id: "medium", label: "Medium", tone: "warning" },
  { id: "low", label: "Low", tone: "neutral" },
];
const RANK = { high: 0, medium: 1, low: 2 };

export const BUCKETS = [
  { id: "overdue", label: "Overdue" },
  { id: "today", label: "Today" },
  { id: "week", label: "This week" },
  { id: "later", label: "Later" },
  { id: "none", label: "No date" },
  { id: "done", label: "Done" },
];

/** Which group a task goes in, by its due date against today. */
export function bucketOf(task, today = dayKey()) {
  if (task.done) return "done";
  if (!task.dueDate) return "none";
  if (task.dueDate < today) return "overdue";
  if (task.dueDate === today) return "today";
  if (task.dueDate <= addDays(today, 7)) return "week";
  return "later";
}

/** Soonest first, then the more urgent, then the newest. Done ones: most recently done first. */
export function sortTasks(tasks) {
  return [...tasks].sort((a, b) => {
    if (a.done && b.done) return (b.doneAt ?? 0) - (a.doneAt ?? 0);
    if (a.done !== b.done) return a.done ? 1 : -1;
    if ((a.dueDate ?? "9") !== (b.dueDate ?? "9")) return (a.dueDate ?? "9") < (b.dueDate ?? "9") ? -1 : 1;
    if (RANK[a.priority] !== RANK[b.priority]) return (RANK[a.priority] ?? 1) - (RANK[b.priority] ?? 1);
    return (b.createdAt ?? 0) - (a.createdAt ?? 0);
  });
}

const DELETED = Symbol("deleted");

/**
 * The caller's tasks (an admin's: everyone's), polled every minute, with changes shown at once and
 * put back if the server refuses them. A change still on its way is never undone by a poll.
 */
export function useTasks(status = "all") {
  const [state, setState] = useState({ status: "loading", tasks: [] });
  const [attempt, setAttempt] = useState(0);
  const pending = useRef(new Map());

  useEffect(() => poll(
    TASKS_PATH(status),
    (data) => {
      const local = pending.current;
      const tasks = (data.tasks ?? [])
        .filter((t) => local.get(t.id) !== DELETED)
        .map((t) => (local.has(t.id) ? { ...t, ...local.get(t.id) } : t));
      setState({ status: "ready", tasks });
    },
    () => setState((s) => ({ ...s, status: "error" })),
    60000,
  ), [status, attempt]);

  const patchLocal = (id, fields) =>
    setState((s) => ({ ...s, tasks: s.tasks.map((t) => (t.id === id ? { ...t, ...fields } : t)) }));

  async function change(task, fields, failMessage) {
    const before = Object.fromEntries(Object.keys(fields).map((k) => [k, task[k]]));
    pending.current.set(task.id, fields);
    patchLocal(task.id, fields);
    try {
      const saved = await updateTask(task.id, fields);
      if (saved) patchLocal(task.id, saved);
      refreshAll();
      return true;
    } catch (err) {
      patchLocal(task.id, before);
      toast.error(friendlyError(err, failMessage));
      return false;
    } finally {
      pending.current.delete(task.id);
    }
  }

  const toggle = (task) =>
    change(task, { done: !task.done, doneAt: task.done ? null : Date.now() }, "Could not update the task. Try again.");

  const save = (task, fields) => change(task, fields, "Could not save the task. Try again.");

  async function add(fields) {
    try {
      const task = await createTask(fields);
      if (task) setState((s) => ({ ...s, tasks: [task, ...s.tasks] }));
      toast.success("Task added");
      refreshAll();
      return true;
    } catch (err) {
      toast.error(friendlyError(err, "Could not add the task. Try again."));
      return false;
    }
  }

  async function remove(task) {
    pending.current.set(task.id, DELETED);
    setState((s) => ({ ...s, tasks: s.tasks.filter((t) => t.id !== task.id) }));
    try {
      await deleteTask(task.id);
      toast.success("Task deleted");
      refreshAll();
    } catch (err) {
      setState((s) => ({ ...s, tasks: [task, ...s.tasks] }));
      toast.error(friendlyError(err, "Could not delete the task. Try again."));
    } finally {
      pending.current.delete(task.id);
    }
  }

  return { ...state, toggle, save, add, remove, reload: () => setAttempt((n) => n + 1) };
}
