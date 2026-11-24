import { ArrowUp, MessagesSquare, NotebookPen, Play, RotateCcw, SquarePen } from "lucide-react";
import { Fragment, useEffect, useMemo, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";

import { LogoMark } from "../../components/Logo";
import { Segmented, Select, cn } from "../../components/ui";
import { apiFetch, friendlyError } from "../../lib/api";
import { shortDateTime } from "../../lib/dates";
import { clock, titleOf } from "../recordings/format";
import { askNotes } from "./api";

const PROMPTS = {
  all: [
    "What did I decide this week?",
    "Which tasks are overdue, and who owns them?",
    "Summarise my lectures this month",
    "What questions are still open?",
  ],
  note: [
    "Summarise this in 5 bullets",
    "What are the action items?",
    "What did each person push for?",
    "Explain the hardest idea simply",
  ],
};

// The chat outlives the page, so opening a citation and coming back keeps the conversation.
let memory = { scope: "all", noteId: "", messages: [] };

/**
 * A chat over the notes: all of them, or one. Every answer comes only from the notes, with
 * citations that open the note at the moment it was said.
 */
export default function Ask() {
  const [params] = useSearchParams();
  const fromUrl = params.get("note") ?? "";
  const [scope, setScope] = useState(() => (fromUrl ? "note" : memory.scope));
  const [noteId, setNoteId] = useState(() => fromUrl || memory.noteId);
  const [messages, setMessages] = useState(() => (fromUrl && fromUrl !== memory.noteId ? [] : memory.messages));
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const notes = useNotes();
  const listRef = useRef(null);
  const inputRef = useRef(null);

  useEffect(() => { memory = { scope, noteId, messages }; }, [scope, noteId, messages]);

  useEffect(() => {
    const el = listRef.current;
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: "smooth" });
  }, [messages, busy]);

  const effectiveNote = scope === "note" ? noteId || notes[0]?.id || "" : "";
  const noteTitle = notes.find((x) => x.id === effectiveNote)?.title;

  function reset(nextScope = scope, nextNote = noteId) {
    setScope(nextScope);
    setNoteId(nextNote);
    setMessages([]);
  }

  async function send(text, base = messages) {
    const question = text.trim();
    if (!question || busy) return;
    if (scope === "note" && !effectiveNote) return;
    const history = base.filter((m) => !m.error).map((m) => ({ role: m.role, content: m.content }));
    const next = [...base, { role: "user", content: question }];
    setMessages(next);
    setDraft("");
    setBusy(true);
    try {
      const res = await askNotes({ question, recordingId: effectiveNote || undefined, history });
      setMessages([...next, { role: "assistant", content: res.answer ?? "", citations: dedupe(res.citations ?? []) }]);
    } catch (err) {
      setMessages([...next, { role: "assistant", content: "", error: friendlyError(err, "Could not get an answer. Try again."), question }]);
    } finally {
      setBusy(false);
      inputRef.current?.focus();
    }
  }

  function retry(i) {
    const failed = messages[i];
    const base = messages.slice(0, i - 1);
    send(failed.question, base);
  }

  const empty = messages.length === 0;

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1>Ask AI</h1>
        {!empty && (
          <button type="button" className="btn-ghost btn-sm" onClick={() => reset()}>
            <SquarePen className="size-4" aria-hidden="true" />
            New chat
          </button>
        )}
      </div>

      <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
        <Segmented
          aria-label="Ask about"
          value={scope}
          onChange={(v) => reset(v)}
          options={[{ value: "all", label: "All notes" }, { value: "note", label: "One note" }]}
          className="self-start"
        />
        {scope === "note" && (
          <Select
            icon={NotebookPen}
            aria-label="Note"
            value={effectiveNote}
            onChange={(v) => reset("note", v)}
            disabled={notes.length === 0}
            options={notes.length ? notes.map((x) => ({ value: x.id, label: x.label })) : [{ value: "", label: "No notes yet" }]}
            className="min-w-0 sm:max-w-sm [&_select]:truncate"
          />
        )}
      </div>

      <section className="card flex h-[calc(100dvh-17rem)] min-h-[440px] flex-col overflow-hidden lg:h-[calc(100dvh-15rem)]">
        <div ref={listRef} className="flex min-h-0 flex-1 flex-col gap-5 overflow-y-auto px-4 py-5 sm:px-6">
          {empty ? (
            <div className="m-auto flex max-w-lg flex-col items-center text-center">
              <span className="flex size-12 items-center justify-center rounded-2xl bg-primary-tint text-primary">
                <MessagesSquare className="size-6" aria-hidden="true" />
              </span>
              <h2 className="mt-4 text-lg font-extrabold tracking-tight">
                {scope === "note" ? "Ask about this note" : "Ask anything about your notes"}
              </h2>
              <p className="mt-1 text-sm text-ink-muted">
                {scope === "note" && noteTitle ? noteTitle : "Answers cite the note and the moment."}
              </p>
              <div className="mt-6 flex flex-wrap justify-center gap-2">
                {PROMPTS[scope].map((p) => (
                  <button key={p} type="button" className="pill h-auto min-h-9 whitespace-normal py-2 text-left text-ink" onClick={() => send(p)}>
                    {p}
                  </button>
                ))}
              </div>
            </div>
          ) : (
            messages.map((m, i) => (
              <Message key={i} m={m} onRetry={() => retry(i)} busy={busy} />
            ))
          )}
          {busy && (
            <div className="flex items-start gap-3" role="status" aria-label="Thinking">
              <LogoMark size={28} className="shrink-0" />
              <div className="flex h-10 items-center gap-1.5 rounded-2xl rounded-tl-md bg-bg px-4">
                <span className="typing-dot size-1.5 rounded-full bg-ink-muted" />
                <span className="typing-dot size-1.5 rounded-full bg-ink-muted" />
                <span className="typing-dot size-1.5 rounded-full bg-ink-muted" />
              </div>
            </div>
          )}
        </div>

        <form
          className="border-t border-line p-3 sm:p-4"
          onSubmit={(e) => { e.preventDefault(); send(draft); }}
        >
          <div className="flex items-end gap-2 rounded-3xl border border-line bg-surface-alt py-1.5 pl-4 pr-1.5 transition-colors focus-within:border-primary focus-within:bg-surface">
            <textarea
              ref={inputRef}
              rows={1}
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(draft); } }}
              placeholder={scope === "note" ? "Ask about this note" : "Ask about your notes"}
              aria-label="Your question"
              maxLength={1000}
              className="field-sizing-content max-h-32 min-h-9 flex-1 resize-none bg-transparent py-2 text-sm text-ink outline-none placeholder:text-ink-faint max-sm:text-base"
            />
            <button
              type="submit"
              disabled={!draft.trim() || busy || (scope === "note" && !effectiveNote)}
              className="flex size-9 shrink-0 items-center justify-center rounded-full bg-primary text-white transition-colors hover:bg-primary-pressed disabled:bg-ink-faint/40"
              aria-label="Send"
            >
              <ArrowUp className="size-4" strokeWidth={2.5} aria-hidden="true" />
            </button>
          </div>
        </form>
      </section>
    </div>
  );
}

function Message({ m, onRetry, busy }) {
  if (m.role === "user") {
    return (
      <div className="flex justify-end">
        <p className="max-w-[85%] whitespace-pre-wrap break-words rounded-2xl rounded-br-md bg-primary px-4 py-2.5 text-sm leading-relaxed text-white">
          {m.content}
        </p>
      </div>
    );
  }
  return (
    <div className="flex items-start gap-3">
      <LogoMark size={28} className="shrink-0" />
      <div className="min-w-0 max-w-[90%] flex-1">
        {m.error ? (
          <div className="flex flex-wrap items-center gap-3 rounded-2xl rounded-tl-md bg-record-tint px-4 py-3 text-sm text-record">
            <span className="font-semibold">{m.error}</span>
            <button type="button" className="btn-secondary btn-sm" onClick={onRetry} disabled={busy}>
              <RotateCcw className="size-3.5" aria-hidden="true" />
              Retry
            </button>
          </div>
        ) : (
          <>
            <div className="flex flex-col gap-2.5 rounded-2xl rounded-tl-md bg-bg px-4 py-3 text-sm leading-relaxed text-ink">
              <Answer text={m.content} />
            </div>
            {m.citations?.length > 0 && (
              <div className="mt-2 flex flex-wrap gap-1.5">
                {m.citations.map((c, i) => <Citation key={i} c={c} />)}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}

function Citation({ c }) {
  const at = Number.isFinite(c.atSeconds) && c.atSeconds >= 0 ? Math.floor(c.atSeconds) : null;
  return (
    <Link
      to={`/recordings/${c.recordingId}${at !== null ? `?t=${at}` : ""}`}
      title={c.quote ? `"${c.quote}"` : undefined}
      className="inline-flex h-7 max-w-full items-center gap-1.5 rounded-full border border-line bg-surface pl-2 pr-2.5 text-xs font-semibold text-ink-muted transition-colors hover:border-primary hover:text-primary"
    >
      {at !== null ? <Play className="size-3 shrink-0 text-primary" aria-hidden="true" /> : <NotebookPen className="size-3 shrink-0 text-primary" aria-hidden="true" />}
      <span className="truncate">{c.title || "Note"}</span>
      {at !== null && <span className="num shrink-0 font-bold text-primary">{clock(at)}</span>}
    </Link>
  );
}

/** The answer's plain text with light formatting: paragraphs, "- " lists and **bold**. Never HTML. */
function Answer({ text }) {
  const blocks = useMemo(() => {
    const out = [];
    for (const line of String(text).split("\n")) {
      const item = /^\s*(?:[-*•]|\d+[.)])\s+(.*)$/.exec(line);
      const last = out[out.length - 1];
      if (item) {
        if (last?.type === "list") last.items.push(item[1]);
        else out.push({ type: "list", ordered: /^\s*\d/.test(line), items: [item[1]] });
      } else if (line.trim()) {
        if (last?.type === "p" && !last.closed) last.text += ` ${line.trim()}`;
        else out.push({ type: "p", text: line.trim() });
      } else if (last) {
        last.closed = true;
      }
    }
    return out;
  }, [text]);

  if (!blocks.length) return <p className="text-ink-muted">No answer came back.</p>;
  return blocks.map((b, i) => {
    if (b.type === "p") return <p key={i}><Inline text={b.text} /></p>;
    const List = b.ordered ? "ol" : "ul";
    return (
      <List key={i} className={cn("flex flex-col gap-1 pl-5", b.ordered ? "list-decimal" : "list-disc marker:text-primary")}>
        {b.items.map((it, j) => <li key={j}><Inline text={it} /></li>)}
      </List>
    );
  });
}

function Inline({ text }) {
  const parts = String(text).split(/(\*\*[^*]+\*\*)/g);
  return parts.map((p, i) => (p.startsWith("**") && p.endsWith("**")
    ? <strong key={i} className="font-bold">{p.slice(2, -2)}</strong>
    : <Fragment key={i}>{p}</Fragment>));
}

function dedupe(citations) {
  const seen = new Set();
  return citations.filter((c) => {
    if (!c?.recordingId) return false;
    const k = `${c.recordingId}|${c.atSeconds ?? ""}`;
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  }).slice(0, 8);
}

/** Ready notes from the last year, newest first, for the one-note picker. */
function useNotes() {
  const [notes, setNotes] = useState([]);
  useEffect(() => {
    let live = true;
    const to = Date.now() + 2 * 86400000;
    const from = to - 367 * 86400000;
    apiFetch(`/api/recordings?from=${from}&to=${to}`)
      .then((data) => {
        if (!live) return;
        setNotes((data.rows ?? [])
          .filter((r) => r.status === "ready" && !r.noSpeech)
          .map((r) => {
            const title = titleOf(r) || "Untitled note";
            return { id: r.id, title, label: `${title} · ${shortDateTime(r.meetingAt)}` };
          }));
      })
      .catch(() => undefined);
    return () => { live = false; };
  }, []);
  return notes;
}
