import { getFirestore, type Timestamp } from "firebase-admin/firestore";

import { SETTINGS_PATH } from "./common.js";
import { DEFAULT_MODEL_ID, modelById } from "./models.js";
import { SUMMARY_LANGUAGES, type SummaryLanguage } from "./summary/prompt.js";

/**
 * How summaries are made, as chosen on the admin's settings page (`notetakerOps/settings`). Only the API writes
 * it, after checking every value; anything unrecognised here reads as the default. An old `batch` field on the
 * document is ignored: summaries always run directly.
 */

export type RecordingSettings = {
  summaryModel: string;
  language: SummaryLanguage;
  updatedAt: Timestamp | null;
  updatedBy: string | null;
  updatedByName: string | null;
};

export const DEFAULT_SETTINGS: RecordingSettings = {
  summaryModel: DEFAULT_MODEL_ID,
  language: "en",
  updatedAt: null,
  updatedBy: null,
  updatedByName: null,
};

export async function readSettings(): Promise<RecordingSettings> {
  const snap = await getFirestore().doc(SETTINGS_PATH).get();
  const d = snap.data() ?? {};
  return {
    summaryModel: modelById(d.summaryModel) ? (d.summaryModel as string) : DEFAULT_SETTINGS.summaryModel,
    language: (SUMMARY_LANGUAGES as readonly string[]).includes(d.language)
      ? (d.language as SummaryLanguage)
      : DEFAULT_SETTINGS.language,
    updatedAt: (d.updatedAt as Timestamp | undefined) ?? null,
    updatedBy: typeof d.updatedBy === "string" ? d.updatedBy : null,
    updatedByName: typeof d.updatedByName === "string" ? d.updatedByName : null,
  };
}
