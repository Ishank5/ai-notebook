import OpenAI from "openai";

import { classifyHttp } from "../common.js";
import type { SummaryModel } from "../models.js";
import { failed, retry, type SummaryOutcome } from "./outcome.js";
import { ANALYSIS_SCHEMA, AnalysisParseError, parseAnalysisText, type Prompt } from "./prompt.js";

/**
 * Summaries from OpenAI, one call per meeting — the same prompt and schema as
 * Claude, through the Responses API's structured output.
 *
 * KEPT APART FROM THE CLAUDE MODULE on purpose: the two SDKs look alike and
 * are not, and one file per provider keeps either from borrowing the other's
 * assumptions.
 */

function client(apiKey: string): OpenAI {
  return new OpenAI({
    apiKey,
    maxRetries: 2,
    timeout: 15 * 60 * 1000,
  });
}

export function responseBody(model: SummaryModel, prompt: Prompt, maxTokens = model.maxOutputTokens) {
  return {
    model: model.id,
    instructions: prompt.system,
    input: prompt.user,
    text: {
      format: {
        type: "json_schema" as const,
        name: "meeting_record",
        schema: ANALYSIS_SCHEMA,
        strict: true,
      },
    },
    ...(model.effort ? { reasoning: { effort: model.effort } } : {}),
    max_output_tokens: maxTokens,
    // Nothing about these meetings is kept on OpenAI's side beyond what the
    // request itself requires.
    store: false,
  };
}

type RawResponse = {
  status?: unknown;
  incomplete_details?: { reason?: unknown } | null;
  error?: { message?: unknown } | null;
  output?: unknown;
  usage?: { input_tokens?: unknown; output_tokens?: unknown } | null;
};

/**
 * A response body. It reads `output[]` itself rather than the SDK's
 * `output_text`, so it works on any plain response object.
 */
export function readResponse(raw: unknown, audioSecs: number | null): SummaryOutcome {
  const resp = (raw ?? {}) as RawResponse;
  if (resp.status === "incomplete") {
    const reason = String(resp.incomplete_details?.reason ?? "unknown");
    if (reason === "content_filter") return failed("summary.refused", "OpenAI's content filter stopped the answer.");
    return retry("summary.truncated", `The answer was cut off (${reason}).`);
  }
  if (resp.status !== "completed") {
    const message = typeof resp.error?.message === "string" ? resp.error.message : `status ${String(resp.status)}`;
    return retry("summary.providerError", message.slice(0, 300));
  }

  let text = "";
  let refusal = "";
  for (const item of Array.isArray(resp.output) ? resp.output : []) {
    const entry = item as { type?: unknown; content?: unknown };
    if (entry.type !== "message" || !Array.isArray(entry.content)) continue;
    for (const part of entry.content as { type?: unknown; text?: unknown; refusal?: unknown }[]) {
      if (part.type === "output_text" && typeof part.text === "string") text += part.text;
      else if (part.type === "refusal" && typeof part.refusal === "string") refusal += part.refusal;
    }
  }
  if (!text && refusal) return failed("summary.refused", refusal.slice(0, 300));

  try {
    return {
      kind: "done",
      analysis: parseAnalysisText(text, audioSecs),
      usage: {
        inputTokens: typeof resp.usage?.input_tokens === "number" ? resp.usage.input_tokens : 0,
        outputTokens: typeof resp.usage?.output_tokens === "number" ? resp.usage.output_tokens : 0,
      },
      stopReason: "completed",
    };
  } catch (err) {
    if (err instanceof AnalysisParseError) return retry("summary.unreadable", err.message);
    throw err;
  }
}

function rethrow(err: unknown): never {
  if (err instanceof OpenAI.APIError) {
    throw classifyHttp(err.status, "summary", err.message.slice(0, 300));
  }
  throw err;
}

export async function runOpenAI(
  apiKey: string,
  model: SummaryModel,
  prompt: Prompt,
  audioSecs: number | null,
  maxTokens?: number,
): Promise<SummaryOutcome> {
  let resp: unknown;
  try {
    resp = await client(apiKey).responses.create(responseBody(model, prompt, maxTokens));
  } catch (err) {
    rethrow(err);
  }
  return readResponse(resp, audioSecs);
}
