import { Flag, ListChecks, Plus, Search } from "lucide-react";
import { useMemo, useState } from "react";

import { Btn, Confirm, EmptyState, ErrorState, Segmented, Select, Skeleton, cn } from "../../components/ui";
import { useAuth } from "../../lib/auth";
import { dayKey } from "../../lib/dates";
import { TaskForm, TaskRow } from "./TaskRow";
import { BUCKETS, PRIORITIES, bucketOf, sortTasks, useTasks } from "./useTasks";

/**
 * Every action item in one place, from the AI and added by hand. Grouped by when it is due, ticked
 * off in place, edited in place. Members see their own; admins everyone's.
 */
export default function Tasks() {
  const { profile } = useAuth();
  const { status, tasks, toggle, save, add, remove, reload } = useTasks("all");
  const [view, setView] = useState("open");
  const [search, setSearch] = useState("");
  const [priority, setPriority] = useState("");
  const [adding, setAdding] = useState(false);
  const [deleting, setDeleting] = useState(null);

  const counts = useMemo(() => ({
    open: tasks.filter((t) => !t.done).length,
    done: tasks.filter((t) => t.done).length,
    all: tasks.length,
  }), [tasks]);

  const groups = useMemo(() => {
    const needle = search.trim().toLowerCase();
    const today = dayKey();
    const shown = tasks.filter((t) => {
      if (view === "open" && t.done) return false;
      if (view === "done" && !t.done) return false;
      if (priority && t.priority !== priority) return false;
      if (needle && ![t.text, t.owner, t.recordingTitle, t.ownerName].filter(Boolean).join(" ").toLowerCase().includes(needle)) return false;
      return true;
    });
    const byBucket = new Map(BUCKETS.map((b) => [b.id, []]));
    for (const t of sortTasks(shown)) byBucket.get(bucketOf(t, today)).push(t);
    return BUCKETS.map((b) => ({ ...b, tasks: byBucket.get(b.id) })).filter((g) => g.tasks.length > 0);
  }, [tasks, view, search, priority]);

  const filtered = search.trim() || priority;

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-baseline gap-3">
          <h1>Tasks</h1>
          {status === "ready" && <span className="num text-sm font-semibold text-ink-faint">{counts.open} open</span>}
        </div>
        <Btn onClick={() => setAdding(true)} disabled={adding}>
          <Plus className="size-4" aria-hidden="true" />
          Add task
        </Btn>
      </div>

      <div className="flex flex-col gap-3 md:flex-row md:items-center">
        <Segmented
          aria-label="Show"
          value={view}
          onChange={setView}
          options={[
            { value: "open", label: "Open", count: status === "ready" ? counts.open : undefined },
            { value: "done", label: "Done", count: status === "ready" ? counts.done : undefined },
            { value: "all", label: "All", count: status === "ready" ? counts.all : undefined },
          ]}
          className="self-start"
        />
        <div className="relative md:max-w-xs md:flex-1">
          <Search className="pointer-events-none absolute left-4 top-1/2 size-4 -translate-y-1/2 text-ink-faint" aria-hidden="true" />
          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search tasks"
            aria-label="Search tasks"
            className="field border-line bg-surface pl-11"
          />
        </div>
        <Select
          icon={Flag}
          aria-label="Priority"
          value={priority}
          onChange={setPriority}
          options={[{ value: "", label: "Any priority" }, ...PRIORITIES.map((p) => ({ value: p.id, label: p.label }))]}
          className="self-start"
        />
      </div>

      {adding && (
        <section className="card card-pad">
          <h2 className="mb-3">New task</h2>
          <TaskForm
            initial={{ owner: profile?.name ?? "" }}
            submitLabel="Add task"
            onCancel={() => setAdding(false)}
            onSubmit={async (fields) => {
              const ok = await add(fields);
              if (ok) setAdding(false);
              return ok;
            }}
          />
        </section>
      )}

      {status === "loading" && <TasksSkeleton />}
      {status === "error" && <ErrorState title="Could not load tasks" body="Check the connection and try again." onRetry={reload} />}
      {status === "ready" && groups.length === 0 && (
        <EmptyState
          icon={ListChecks}
          title={filtered ? "Nothing matches" : view === "done" ? "Nothing done yet" : tasks.length === 0 ? "No tasks yet" : "All clear"}
          hint={filtered ? "Try another search or priority." : tasks.length === 0 ? "Action items from your notes land here, or add one yourself." : view === "open" ? "Every task is done." : ""}
        />
      )}

      {status === "ready" && groups.map((g) => (
        <section key={g.id} className="card overflow-hidden">
          <div className="flex items-center gap-2 border-b border-line px-4 py-3 sm:px-5">
            <h2 className={cn(g.id === "overdue" && "text-record")}>{g.label}</h2>
            <span className="num text-xs font-semibold text-ink-faint">{g.tasks.length}</span>
          </div>
          <ul className="divide-y divide-line">
            {g.tasks.map((t) => (
              <TaskRow key={t.id} task={t} onToggle={toggle} onSave={save} onDelete={setDeleting} />
            ))}
          </ul>
        </section>
      ))}

      <Confirm
        open={Boolean(deleting)}
        danger
        title="Delete this task?"
        confirmLabel="Delete"
        onCancel={() => setDeleting(null)}
        onConfirm={() => { const t = deleting; setDeleting(null); remove(t); }}
      >
        {deleting?.text}
      </Confirm>
    </div>
  );
}

function TasksSkeleton() {
  return (
    <div className="card flex flex-col gap-5 p-5" role="status" aria-label="Loading tasks">
      <Skeleton className="h-4 w-24" />
      {[0, 1, 2, 3].map((i) => (
        <div key={i} className="flex items-start gap-3">
          <Skeleton className="size-[22px] rounded-full" />
          <div className="flex flex-1 flex-col gap-2">
            <Skeleton className="h-4 w-3/4" />
            <Skeleton className="h-3 w-1/3" />
          </div>
        </div>
      ))}
    </div>
  );
}
