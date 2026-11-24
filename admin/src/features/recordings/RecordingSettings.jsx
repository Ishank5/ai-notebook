import { CircleCheck, CircleX, FlaskConical } from "lucide-react";
import { useEffect, useState } from "react";
import { toast } from "sonner";

import { Btn, Chip, ErrorState, Loading, cn } from "../../components/ui";
import { errorKey, getSettings, saveSettings, testModel } from "./api";
import { useFormat } from "./fmt";
import { errorLabel, speakerNames } from "./format";
import { t } from "./strings";
import { SummaryView } from "./Summary";

const KEYS = [
  ["elevenlabs", "settings.keyElevenlabs"],
  ["webhook", "settings.keyWebhook"],
  ["anthropic", "settings.keyAnthropic"],
  ["openai", "settings.keyOpenai"],
];

/**
 * Which model writes the summaries. A change applies from the next summary. Only models whose provider has a key can be chosen. Test runs a short sample
 * meeting through the selected model and shows what it wrote, with its time and cost.
 */
export default function RecordingSettings() {
  const { dateTime } = useFormat();
  const [state, setState] = useState({ status: "loading", data: null });
  const [draft, setDraft] = useState(null);
  const [saving, setSaving] = useState(false);
  const [test, setTest] = useState({ status: "idle" });

  useEffect(() => {
    let live = true;
    getSettings()
      .then((data) => {
        if (!live) return;
        setState({ status: "ready", data });
        setDraft({ ...data.settings });
      })
      .catch((err) => { if (live) setState({ status: "error", data: null, errorKey: errorKey(err) }); });
    return () => { live = false; };
  }, []);

  if (state.status === "loading") return <Frame><div className="card"><Loading rows={4} label={t("loading")} /></div></Frame>;
  if (state.status === "error") return <Frame><ErrorState title={t("settings.errorTitle")} body={t(state.errorKey)} /></Frame>;

  const { models = [], keys = {}, settings = {}, transcription } = state.data;
  const changed = draft && draft.summaryModel !== settings.summaryModel;
  const selected = models.find((m) => m.id === draft.summaryModel);
  const providers = [...new Set(models.map((m) => m.provider))];

  async function save() {
    setSaving(true);
    try {
      const data = await saveSettings({ summaryModel: draft.summaryModel });
      setState({ status: "ready", data });
      setDraft({ ...data.settings });
      toast.success(t("settings.saved"));
    } catch (err) {
      toast.error(t(errorKey(err)));
    } finally {
      setSaving(false);
    }
  }

  async function runTest() {
    setTest({ status: "running", model: draft.summaryModel });
    try {
      const result = await testModel(draft.summaryModel);
      setTest({ status: "done", ...result });
    } catch (err) {
      setTest({ status: "done", ok: false, code: errorKey(err), message: "" });
    }
  }

  return (
    <Frame>
      <div className="grid items-start gap-4 lg:grid-cols-3 lg:gap-6">
        <div className="flex flex-col gap-4 lg:col-span-2">
          <section className="card card-pad flex flex-col gap-5">
            <h2>{t("settings.model")}</h2>
            {providers.map((provider) => (
              <fieldset key={provider} className="flex flex-col gap-2">
                <legend className="label mb-2">
                  {t(`settings.${provider}`, { defaultValue: provider })}
                  {keys[provider] === false && ` · ${t("settings.noKey")}`}
                </legend>
                {models.filter((m) => m.provider === provider).map((m) => {
                  const on = draft.summaryModel === m.id;
                  return (
                    <label
                      key={m.id}
                      className={cn(
                        "flex cursor-pointer items-start gap-3 rounded-2xl border px-4 py-3 transition-colors",
                        on ? "border-primary bg-primary-tint/60" : "border-line hover:bg-bg",
                        !m.available && "cursor-not-allowed opacity-50",
                      )}
                    >
                      <input
                        type="radio"
                        name="model"
                        className="mt-1 size-4 accent-[var(--color-primary)]"
                        checked={on}
                        disabled={!m.available}
                        onChange={() => setDraft((d) => ({ ...d, summaryModel: m.id }))}
                      />
                      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                        <span className="flex flex-wrap items-center gap-2">
                          <span className="text-sm font-bold">{m.label}</span>
                          {m.id === settings.summaryModel && <Chip size="sm" tone="success">{t("settings.current")}</Chip>}
                        </span>
                        {m.note && <span className="text-[13px] text-ink-muted">{m.note}</span>}
                        {m.price && (
                          <span className="num text-xs text-ink-faint">
                            {t("settings.price", { input: price(m.price.input), output: price(m.price.output) })}
                          </span>
                        )}
                      </span>
                    </label>
                  );
                })}
              </fieldset>
            ))}

            <div className="flex flex-wrap items-center gap-3">
              <Btn onClick={save} loading={saving} disabled={!changed}>{t("settings.save")}</Btn>
              <Btn variant="outline" onClick={runTest} loading={test.status === "running"} disabled={!selected?.available}>
                <FlaskConical className="size-4" aria-hidden="true" />
                {t("settings.test")}
              </Btn>
              {settings.updatedByName && (
                <span className="text-xs text-ink-faint">
                  {t("settings.lastChanged", { name: settings.updatedByName, when: settings.updatedAt ? dateTime(settings.updatedAt) : "-" })}
                </span>
              )}
            </div>
          </section>

          {test.status !== "idle" && (
            <section className="card card-pad flex flex-col gap-4">
              <h2>{t("settings.testTitle")}</h2>
              {test.status === "running" && <Loading rows={3} label={t("settings.testing")} />}
              {test.status === "done" && test.ok && (
                <>
                  <p className="num text-xs text-ink-faint">
                    {t("settings.testResult", {
                      model: test.model,
                      seconds: test.seconds,
                      input: test.usage?.inputTokens ?? 0,
                      output: test.usage?.outputTokens ?? 0,
                      cost: Number(test.costUsd ?? 0).toFixed(3),
                    })}
                  </p>
                  <SummaryView analysis={test.analysis} names={speakerNames(test.analysis)} flat showTitle />
                </>
              )}
              {test.status === "done" && !test.ok && (
                <div role="alert" className="rounded-2xl bg-record-tint px-4 py-3 text-sm text-record">
                  <p className="font-semibold">{test.code?.startsWith("err.") ? t(test.code) : errorLabel(t, test.code ?? "unknown")}</p>
                  {test.message && <p className="mt-1">{test.message}</p>}
                </div>
              )}
            </section>
          )}
        </div>

        <section className="card card-pad flex flex-col gap-4">
          <div>
            <h2>{t("settings.pipeline")}</h2>
            {transcription?.provider && (
              <p className="mt-1 text-[13px] text-ink-muted">{t("settings.pipelineHint", { provider: transcription.provider, model: transcription.model ?? "-" })}</p>
            )}
          </div>
          <ul className="flex flex-col divide-y divide-line text-sm">
            {KEYS.filter(([k]) => k in keys).map(([k, label]) => {
              const ok = Boolean(keys[k]);
              const Icon = ok ? CircleCheck : CircleX;
              return (
                <li key={k} className="flex items-center gap-3 py-2.5 first:pt-0 last:pb-0">
                  <Icon className={cn("size-4", ok ? "text-success" : "text-record")} aria-hidden="true" />
                  <span className="flex-1">{t(label)}</span>
                  <span className={cn("text-xs font-semibold", ok ? "text-success" : "text-record")}>{ok ? t("settings.keySet") : t("settings.keyMissing")}</span>
                </li>
              );
            })}
          </ul>
        </section>
      </div>
    </Frame>
  );
}

function Frame({ children }) {
  return (
    <div className="flex flex-col gap-6">
      <h1>{t("settings.title")}</h1>
      {children}
    </div>
  );
}

/** $4 → "$4", $2.5 → "$2.50", $0.05 → "$0.05". */
function price(n) {
  return `$${Number.isInteger(n) ? n : n.toFixed(2)}`;
}
