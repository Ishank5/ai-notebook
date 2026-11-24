import { Copy, Mail, RotateCcw } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { toast } from "sonner";

import { Btn, Modal, Segmented, Skeleton } from "../../components/ui";
import { friendlyError } from "../../lib/api";
import { draftEmail } from "./api";

/**
 * A follow-up email drafted from the note, in a formal or friendly tone. The draft can be edited,
 * copied, or opened in the mail app. Each tone is drafted once per opening and kept.
 */
export function EmailModal({ open, onClose, recordingId }) {
  const [tone, setTone] = useState("friendly");
  const [drafts, setDrafts] = useState({});
  const asked = useRef(new Set());
  const draft = drafts[tone];

  async function fetchDraft(which) {
    asked.current.add(which);
    setDrafts((d) => ({ ...d, [which]: { status: "loading" } }));
    try {
      const res = await draftEmail(recordingId, which);
      setDrafts((d) => ({ ...d, [which]: { status: "ready", subject: res.subject ?? "", body: res.body ?? "" } }));
    } catch (err) {
      setDrafts((d) => ({ ...d, [which]: { status: "error", message: friendlyError(err, "Could not draft the email. Try again.") } }));
    }
  }

  useEffect(() => {
    if (!open || asked.current.has(tone)) return undefined;
    const id = setTimeout(() => fetchDraft(tone), 0);
    return () => clearTimeout(id);
    // fetchDraft only reads the id and the tone it is given.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, tone]);

  const edit = (field) => (e) => setDrafts((d) => ({ ...d, [tone]: { ...d[tone], [field]: e.target.value } }));

  async function copy() {
    try {
      await navigator.clipboard.writeText(`Subject: ${draft.subject}\n\n${draft.body}`);
      toast.success("Email copied");
    } catch {
      toast.error("Could not copy. Select the text and copy it instead.");
    }
  }

  const ready = draft?.status === "ready";
  const mailto = ready ? `mailto:?subject=${encodeURIComponent(draft.subject)}&body=${encodeURIComponent(draft.body)}` : undefined;

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Follow-up email"
      wide
      footer={
        <>
          <Btn variant="outline" onClick={copy} disabled={!ready}>
            <Copy className="size-4" aria-hidden="true" />
            Copy
          </Btn>
          <a href={mailto} className={ready ? "btn-primary" : "btn-primary pointer-events-none opacity-50"} aria-disabled={!ready}>
            <Mail className="size-4" aria-hidden="true" />
            Open in mail
          </a>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <Segmented aria-label="Tone" value={tone} onChange={setTone}
            options={[{ value: "friendly", label: "Friendly" }, { value: "formal", label: "Formal" }]} />
          <Btn variant="ghost" size="sm" onClick={() => fetchDraft(tone)} disabled={draft?.status === "loading"}>
            <RotateCcw className="size-3.5" aria-hidden="true" />
            Redraft
          </Btn>
        </div>

        {(!draft || draft.status === "loading") && (
          <div className="flex flex-col gap-3" role="status" aria-label="Drafting the email">
            <Skeleton className="h-10 rounded-full" />
            <Skeleton className="h-64 rounded-2xl" />
          </div>
        )}
        {draft?.status === "error" && (
          <div className="flex flex-col items-start gap-3 rounded-2xl bg-record-tint px-4 py-4">
            <p className="text-sm font-semibold text-record">{draft.message}</p>
            <Btn variant="outline" size="sm" onClick={() => fetchDraft(tone)}>Try again</Btn>
          </div>
        )}
        {ready && (
          <>
            <label className="flex flex-col gap-1.5">
              <span className="label">Subject</span>
              <input className="field" value={draft.subject} onChange={edit("subject")} />
            </label>
            <label className="flex flex-col gap-1.5">
              <span className="label">Body</span>
              <textarea
                className="min-h-72 w-full resize-y rounded-2xl border border-transparent bg-surface-alt px-4 py-3 text-sm leading-relaxed text-ink outline-none focus:border-primary focus:bg-surface max-sm:text-base"
                value={draft.body}
                onChange={edit("body")}
                rows={14}
              />
            </label>
          </>
        )}
      </div>
    </Modal>
  );
}
