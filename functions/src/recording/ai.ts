import Anthropic from "@anthropic-ai/sdk";
import type { Message, MessageParam } from "@anthropic-ai/sdk/resources/messages/messages";
import * as logger from "firebase-functions/logger";

import { ANTHROPIC_API_KEY, HttpsError, describe, keyIsSet } from "./common.js";
import { DEFAULT_MODEL_ID, modelById, type SummaryModel } from "./models.js";
import { readSettings } from "./settings.js";
import { streamMessage } from "./summary/anthropic.js";
import { jsonObjectIn } from "./summary/prompt.js";

/**
 * One direct Claude call for the features built on the notes: insights, Ask AI and the follow-up email.
 *
 * SAME CLIENT AS THE SUMMARIES: streamed, with the refusal fallback, the schema in the system prompt and a JSON
 * answer read loosely. The model is the settings' summary model when it is a Claude one, else the default Claude
 * model (these features are Claude only). Effort is kept modest: somebody is waiting on the page.
 */

export type Effort = "low" | "medium" | "high";

/** The settings' summary model when it is a Claude one; the default Claude model otherwise. */
export async function featureModel(): Promise<SummaryModel> {
  const chosen = modelById((await readSettings()).summaryModel);
  return chosen?.provider === "anthropic" ? chosen : (modelById(DEFAULT_MODEL_ID) as SummaryModel);
}

/** The answer's text. Refusals, cut-offs and provider errors become API refusals the panel can show. */
export async function askClaude(opts: {
  system: string;
  messages: MessageParam[];
  maxTokens: number;
  effort?: Effort;
}): Promise<string> {
  const key = ANTHROPIC_API_KEY.value();
  if (!keyIsSet(key)) throw new HttpsError("failed-precondition", "recordings.err.noKey");
  const model = await featureModel();

  let message: Message;
  try {
    message = await streamMessage(key, model, {
      model: model.id,
      max_tokens: opts.maxTokens,
      system: opts.system,
      messages: opts.messages,
      ...(model.effort ? { output_config: { effort: opts.effort ?? model.effort } } : {}),
    });
  } catch (err) {
    logger.error("AI call failed", { model: model.id, error: describe(err) });
    if (err instanceof Anthropic.APIError && (err.status === 401 || err.status === 403)) {
      throw new HttpsError("failed-precondition", "recordings.err.noKey");
    }
    throw new HttpsError("unavailable", "recordings.err.unknown");
  }

  if (message.stop_reason === "refusal" || message.stop_reason === "max_tokens") {
    logger.warn("AI answer not usable", { model: model.id, stopReason: message.stop_reason });
    throw new HttpsError("internal", "recordings.err.unknown");
  }
  return message.content.map((block) => (block.type === "text" ? block.text : "")).join("").trim();
}

/** The same call, for a JSON answer: the parsed object (null when unreadable) and the raw text. */
export async function askClaudeJson(opts: Parameters<typeof askClaude>[0]): Promise<{ json: Record<string, unknown> | null; text: string }> {
  const text = await askClaude(opts);
  return { json: jsonObjectIn(text), text };
}

/** A string from the model, trimmed and clamped, with em dashes turned into plain ones (the UI uses none). */
export function clean(value: unknown, max: number): string {
  return typeof value === "string" ? value.replace(/\s*—\s*/g, " - ").trim().slice(0, max) : "";
}

/** A transcript moment from the model: a whole number of seconds, or null. */
export function secondsOrNull(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 ? Math.floor(value) : null;
}
