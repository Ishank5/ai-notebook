import Anthropic from "@anthropic-ai/sdk";
import type {
  Message,
  MessageCreateParamsNonStreaming,
} from "@anthropic-ai/sdk/resources/messages/messages";

import { classifyHttp } from "../common.js";
import type { SummaryModel } from "../models.js";
import { failed, retry, type SummaryOutcome } from "./outcome.js";
import { ANSWER_FORMAT, AnalysisParseError, parseAnalysisText, type Prompt } from "./prompt.js";

/**
 * Summaries from Claude, one call per meeting, streamed.
 *
 * THE SCHEMA IS IN THE SYSTEM PROMPT, and Claude answers with JSON only, which
 * `parseAnalysisText` reads loosely (Render's approach). A grammar-constrained
 * `output_config.format` was refused as too large for a bigger schema,
 * and a forced tool call is refused by the current models.
 *
 * REFUSAL FALLBACK. Calls on models that support it send
 * `fallbacks: "default"`, so a safety decline is re-run on a fallback model
 * inside the same call.
 *
 * THINKING IS LEFT TO THE MODEL. Opus 5.5 always thinks and takes no switch
 * for it; `effort` is the control, set per model in models.ts. The thinking
 * counts against `max_tokens`, which is why that is generous.
 *
 * STOP REASON IS CHECKED BEFORE THE ANSWER IS READ. An answer cut off at the
 * limit is not a shorter summary, it is a broken one — the old analysis never
 * looked, and would have stored it as complete.
 */

function client(apiKey: string): Anthropic {
  return new Anthropic({
    apiKey,
    maxRetries: 2,
    timeout: 15 * 60 * 1000,
  });
}

export function messageParams(
  model: SummaryModel,
  prompt: Prompt,
  maxTokens = model.maxOutputTokens,
): MessageCreateParamsNonStreaming {
  return {
    model: model.id,
    max_tokens: maxTokens,
    system: `${prompt.system}\n\n${ANSWER_FORMAT}`,
    messages: [{ role: "user", content: prompt.user }],
    ...(model.effort ? { output_config: { effort: model.effort } } : {}),
  };
}

export function readMessage(message: Message, audioSecs: number | null): SummaryOutcome {
  switch (message.stop_reason) {
    case "refusal": {
      const category = (message as { stop_details?: { category?: string | null } | null }).stop_details?.category;
      return failed("summary.refused", `Claude declined to summarise this meeting${category ? ` (${category})` : ""}.`);
    }
    case "max_tokens":
      return retry("summary.truncated", "The answer was cut off at the length limit.");
    case "model_context_window_exceeded":
      return failed("summary.tooLong", "The transcript is longer than the model can read.");
    default:
      break;
  }

  const text = message.content
    .map((block) => (block.type === "text" ? block.text : ""))
    .join("");
  try {
    return {
      kind: "done",
      analysis: parseAnalysisText(text, audioSecs),
      usage: {
        inputTokens:
          message.usage.input_tokens +
          (message.usage.cache_read_input_tokens ?? 0) +
          (message.usage.cache_creation_input_tokens ?? 0),
        outputTokens: message.usage.output_tokens,
      },
      stopReason: message.stop_reason ?? "",
    };
  } catch (err) {
    if (err instanceof AnalysisParseError) return retry("summary.unreadable", err.message);
    throw err;
  }
}

/** An SDK failure as a pipeline error: permanent, busy, or worth a retry. */
function rethrow(err: unknown): never {
  if (err instanceof Anthropic.APIError) {
    throw classifyHttp(err.status, "summary", err.message.slice(0, 300));
  }
  throw err;
}

/**
 * One streamed call, with the refusal fallback on models that take it. The
 * summaries and the newer features (insights, Ask AI, follow-up email) all go
 * through here. SDK errors are thrown as they come.
 */
export async function streamMessage(
  apiKey: string,
  model: SummaryModel,
  params: MessageCreateParamsNonStreaming,
): Promise<Message> {
  return model.fallback
    ? ((await client(apiKey).beta.messages
        .stream({ ...params, betas: ["server-side-fallback-2026-07-01"], fallbacks: "default" })
        .finalMessage()) as unknown as Message)
    : await client(apiKey).messages.stream(params).finalMessage();
}

/**
 * One summary, now. Streamed, because a long meeting's answer plus thinking
 * can run for minutes and an idle HTTP connection is not guaranteed to last.
 */
export async function runAnthropic(
  apiKey: string,
  model: SummaryModel,
  prompt: Prompt,
  audioSecs: number | null,
  maxTokens?: number,
): Promise<SummaryOutcome> {
  let message: Message;
  try {
    message = await streamMessage(apiKey, model, messageParams(model, prompt, maxTokens));
  } catch (err) {
    rethrow(err);
  }
  return readMessage(message, audioSecs);
}
