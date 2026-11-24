import type { Analysis } from "./prompt.js";

/**
 * How one summary request ended, whichever provider ran it.
 *
 * `retry` is for answers worth asking for again — cut off, unreadable, or the
 * provider erroring on its side. `failed` is for answers
 * that would come out the same: a refusal, a request the provider rejects, a
 * key it does not accept. The caller decides how many retries a recording gets.
 */
export type Usage = { inputTokens: number; outputTokens: number };

export type SummaryOutcome =
  | { kind: "done"; analysis: Analysis; usage: Usage; stopReason: string }
  | { kind: "retry"; code: string; message: string }
  | { kind: "failed"; code: string; message: string };

export const retry = (code: string, message: string): SummaryOutcome => ({ kind: "retry", code, message });
export const failed = (code: string, message: string): SummaryOutcome => ({ kind: "failed", code, message });
