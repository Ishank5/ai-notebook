import { RefreshCw } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";

import { NOTE_TYPES } from "../../components/NoteType";
import { Btn, Modal, Select, Skeleton } from "../../components/ui";
import { errorKey, getSettings, resummarize } from "./api";
import { t } from "./strings";

/**
 * Summarise the note again, with another model and, if wanted, as another note type (a meeting
 * re-read as a lecture gets study notes, flashcards and a quiz). Admins only; the API checks.
 */
export function Rerun({ r, currentType, size = "sm", variant = "outline", label = "Re-run" }) {
  const [open, setOpen] = useState(false);
  const [models, setModels] = useState(null);
  const [model, setModel] = useState("");
  const [type, setType] = useState("");
  const [busy, setBusy] = useState(false);
  const running = r.resummarize?.state === "running";

  async function openForm() {
    setOpen(true);
    setType(currentType ?? "");
    if (models) return;
    try {
      const settings = await getSettings();
      const ok = (settings.models ?? []).filter((m) => m.available);
      setModels(ok);
      setModel(r.summaryModel && ok.some((m) => m.id === r.summaryModel) ? r.summaryModel : settings.settings?.summaryModel ?? ok[0]?.id ?? "");
    } catch (err) {
      toast.error(t(errorKey(err)));
      setOpen(false);
    }
  }

  async function submit() {
    setBusy(true);
    try {
      await resummarize(r.id, model || undefined, type && type !== currentType ? type : undefined);
      toast.success(t("resummarize.started"));
      setOpen(false);
    } catch (err) {
      toast.error(t(errorKey(err)));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Btn size={size} variant={variant} onClick={openForm} disabled={running}>
        <RefreshCw className={running ? "size-4 animate-spin" : "size-4"} aria-hidden="true" />
        {running ? "Re-running" : label}
      </Btn>
      <Modal
        open={open}
        onClose={() => setOpen(false)}
        title="Re-run the summary"
        footer={
          <>
            <Btn variant="outline" onClick={() => setOpen(false)}>Cancel</Btn>
            <Btn onClick={submit} loading={busy} disabled={!models || !model}>
              <RefreshCw className="size-4" aria-hidden="true" />
              Re-run
            </Btn>
          </>
        }
      >
        <div className="flex flex-col gap-4">
          <label className="flex flex-col gap-1.5">
            <span className="label">Model</span>
            {models ? (
              <Select aria-label="Model" value={model} onChange={setModel} className="w-full"
                options={models.map((m) => ({ value: m.id, label: m.label ?? m.id }))} />
            ) : <Skeleton className="h-9 rounded-full" />}
          </label>
          <label className="flex flex-col gap-1.5">
            <span className="label">Note type</span>
            <Select aria-label="Note type" value={type} onChange={setType} className="w-full"
              options={[
                ...(currentType ? [] : [{ value: "", label: "Let the AI decide" }]),
                ...NOTE_TYPES.map((n) => ({ value: n.id, label: n.id === currentType ? `${n.label} (now)` : n.label })),
              ]} />
          </label>
          <p className="text-xs text-ink-faint">Ready in a minute or two. The current summary stays until the new one is done.</p>
        </div>
      </Modal>
    </>
  );
}
