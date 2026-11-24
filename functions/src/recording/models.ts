/**
 * The models an admin may choose to write summaries, and nothing else. The settings route refuses anything not
 * here. Prices are list prices in US dollars per million tokens (standard rate). They are only used to estimate
 * what a summary cost.
 */

export type Provider = "anthropic" | "openai";

export type SummaryModel = {
  id: string;
  provider: Provider;
  label: string;
  /** Input and output price per million tokens, standard rate. */
  price: { input: number; output: number };
  /** How hard the model thinks; stated so a provider's default changing does not change the cost. */
  effort?: "low" | "medium" | "high";
  /** Room for the answer and the thinking before it. */
  maxOutputTokens: number;
  /** Claude models that take the server-side refusal fallback (`fallbacks: "default"`), direct calls only. */
  fallback?: boolean;
  note: string;
};

export const SUMMARY_MODELS: readonly SummaryModel[] = [
  {
    id: "claude-sonnet-5-5",
    provider: "anthropic",
    label: "Claude Sonnet 5.5",
    price: { input: 2, output: 10 },
    effort: "medium",
    maxOutputTokens: 32000,
    fallback: true,
    note: "Default. Anthropic's current Sonnet: fast, and strong on long meetings.",
  },
  {
    id: "claude-opus-5-5",
    provider: "anthropic",
    label: "Claude Opus 5.5",
    price: { input: 4, output: 20 },
    effort: "medium",
    maxOutputTokens: 32000,
    fallback: true,
    note: "Anthropic's current Opus. Twice the price of Sonnet 5.5.",
  },
  {
    id: "claude-sonnet-5",
    provider: "anthropic",
    label: "Claude Sonnet 5",
    price: { input: 2, output: 10 },
    effort: "medium",
    maxOutputTokens: 32000,
    note: "The previous Sonnet.",
  },
  {
    id: "claude-opus-5",
    provider: "anthropic",
    label: "Claude Opus 5",
    price: { input: 5, output: 25 },
    effort: "medium",
    maxOutputTokens: 32000,
    fallback: true,
    note: "The previous Opus. Costs more than Opus 5.5.",
  },
  {
    id: "claude-fable-5-1",
    provider: "anthropic",
    label: "Claude Fable 5.1",
    price: { input: 10, output: 50 },
    effort: "medium",
    maxOutputTokens: 32000,
    fallback: true,
    note: "Anthropic's most capable model, at five times the price of Sonnet 5.5.",
  },
  {
    id: "claude-haiku-5-5",
    provider: "anthropic",
    label: "Claude Haiku 5.5",
    price: { input: 0.1, output: 0.5 },
    effort: "medium",
    maxOutputTokens: 32000,
    note: "Anthropic's current Haiku, far cheaper. May miss detail in long meetings.",
  },
  {
    id: "claude-haiku-4-5",
    provider: "anthropic",
    label: "Claude Haiku 4.5",
    price: { input: 1, output: 5 },
    maxOutputTokens: 32000,
    note: "The previous Haiku. Costs more than Haiku 5.5.",
  },
  {
    id: "gpt-6-sol",
    provider: "openai",
    label: "GPT-6 Sol",
    price: { input: 2, output: 10 },
    effort: "medium",
    maxOutputTokens: 32000,
    note: "OpenAI's mid-priced GPT-6 model.",
  },
  {
    id: "gpt-6-astra",
    provider: "openai",
    label: "GPT-6 Astra",
    price: { input: 10, output: 50 },
    effort: "medium",
    maxOutputTokens: 32000,
    note: "OpenAI's most capable model.",
  },
  {
    id: "gpt-6-luna",
    provider: "openai",
    label: "GPT-6 Luna",
    price: { input: 0.1, output: 0.5 },
    effort: "medium",
    maxOutputTokens: 32000,
    note: "OpenAI's cheapest model. May miss detail in long meetings.",
  },
];

export const DEFAULT_MODEL_ID = "claude-sonnet-5-5";

/** Tries at one recording's summary before it is failed. */
export const MAX_SUMMARY_ATTEMPTS = 3;

/** Twice the room, up to 64,000, for a summary asked for again after being cut off. */
export function boostedMaxTokens(model: SummaryModel): number {
  return Math.min(64000, model.maxOutputTokens * 2);
}

export function modelById(id: unknown): SummaryModel | undefined {
  return SUMMARY_MODELS.find((m) => m.id === id);
}

/** The model to use, falling back to the default for anything unknown. */
export function resolveModel(id: unknown): SummaryModel {
  return modelById(id) ?? (modelById(DEFAULT_MODEL_ID) as SummaryModel);
}

/** What one summary cost, estimated from the tokens reported, to a tenth of a cent. */
export function estimateCostUsd(
  model: SummaryModel,
  usage: { inputTokens: number; outputTokens: number },
): number {
  const usd = (usage.inputTokens * model.price.input + usage.outputTokens * model.price.output) / 1_000_000;
  return Math.round(usd * 1000) / 1000;
}
