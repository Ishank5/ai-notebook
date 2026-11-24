import { ANTHROPIC_API_KEY, OPENAI_API_KEY, PermanentError, keyIsSet } from "../common.js";
import type { Provider, SummaryModel } from "../models.js";
import { runAnthropic } from "./anthropic.js";
import { runOpenAI } from "./openai.js";
import type { SummaryOutcome } from "./outcome.js";
import type { Prompt } from "./prompt.js";

/**
 * One door to both providers. Everything above this file asks for "a summary
 * from this model" and never learns which SDK answered.
 *
 * Keys are read here, at call time, from secrets the calling function binds —
 * so a function that never summarises never holds them.
 */

function keyFor(provider: Provider): string {
  const key = provider === "anthropic" ? ANTHROPIC_API_KEY.value() : OPENAI_API_KEY.value();
  if (!keyIsSet(key)) {
    throw new PermanentError(
      "summary.noKey",
      provider === "openai"
        ? "OPENAI_API_KEY is not set, so OpenAI models cannot be used."
        : "ANTHROPIC_API_KEY is not set.",
    );
  }
  return key;
}

export function providerKeyIsSet(provider: Provider): boolean {
  try {
    return keyIsSet(provider === "anthropic" ? ANTHROPIC_API_KEY.value() : OPENAI_API_KEY.value());
  } catch {
    return false;
  }
}

export function runDirect(
  model: SummaryModel,
  prompt: Prompt,
  audioSecs: number | null,
  maxTokens?: number,
): Promise<SummaryOutcome> {
  const key = keyFor(model.provider);
  return model.provider === "anthropic"
    ? runAnthropic(key, model, prompt, audioSecs, maxTokens)
    : runOpenAI(key, model, prompt, audioSecs, maxTokens);
}

export type { SummaryOutcome } from "./outcome.js";
