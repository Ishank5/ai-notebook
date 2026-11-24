/**
 * The recording pipeline's pure parts, against the compiled output.
 *
 *   npm test     (builds first)
 *
 * Nothing here touches Firebase or a provider: these are the rules for
 * cutting transcripts into turns, checking webhook signatures, building the
 * prompt and reading answers.
 */
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { describe, it } from "node:test";

import { isTranscript, metadataOf, verifySignature } from "../lib/recording/elevenlabs.js";
import { DEFAULT_MODEL_ID, SUMMARY_MODELS, boostedMaxTokens, estimateCostUsd, resolveModel } from "../lib/recording/models.js";
import { messageParams, readMessage } from "../lib/recording/summary/anthropic.js";
import { readResponse, responseBody } from "../lib/recording/summary/openai.js";
import {
  ANALYSIS_SCHEMA,
  AnalysisParseError,
  buildPrompt,
  normalizeAnalysis,
  parseAnalysisText,
} from "../lib/recording/summary/prompt.js";
import { clock, normalizeTranscript, transcriptForModel } from "../lib/recording/transcript.js";
import { SAMPLE_TRANSCRIPT } from "../lib/recording/sample.js";

const word = (text, start, end, speaker = "speaker_0", type = "word") => ({ text, start, end, speaker_id: speaker, type });
const space = (start) => ({ text: " ", start, end: start, type: "spacing" });

describe("transcript", () => {
  it("cuts a turn when the speaker changes, and labels speakers in order", () => {
    const t = normalizeTranscript({
      language_code: "pan",
      language_probability: 0.9,
      words: [
        word("ਸਤ", 0, 0.4, "speaker_3"), space(0.4), word("ਸ੍ਰੀ", 0.5, 0.8, "speaker_3"),
        word("hello", 1.0, 1.4, "speaker_1"), space(1.4), word("ji", 1.5, 1.7, "speaker_1"),
      ],
    });
    assert.equal(t.turns.length, 2);
    assert.deepEqual(t.turns.map((x) => x.s), ["S1", "S2"]);
    assert.equal(t.turns[0].text, "ਸਤ ਸ੍ਰੀ");
    assert.equal(t.turns[1].text, "hello ji");
    assert.equal(t.words, 4);
    assert.equal(t.language, "pan");
    assert.deepEqual(t.speakers.map((s) => [s.label, s.rawId, s.words]), [["S1", "speaker_3", 2], ["S2", "speaker_1", 2]]);
  });

  it("cuts a turn at a long pause even when the speaker is the same", () => {
    const t = normalizeTranscript({
      words: [word("one", 0, 0.5), space(0.5), word("two", 4, 4.5)],
    });
    assert.equal(t.turns.length, 2);
    assert.deepEqual(t.turns.map((x) => [x.t0, x.t1]), [[0, 0.5], [4, 4.5]]);
  });

  it("breaks a monologue at a short pause once it has run long", () => {
    const words = [];
    for (let i = 0; i < 60; i++) {
      words.push(word(`w${i}`, i, i + 0.9));
      words.push(space(i + 0.9));
    }
    // A 1.5 s pause at 50 s: long enough once the turn is past 45 s.
    words.push(word("after", 61.5, 62));
    const t = normalizeTranscript({ words });
    assert.equal(t.turns.length, 2);
    assert.equal(t.turns[1].text, "after");
  });

  it("keeps a speakerless sound inside the current turn", () => {
    const t = normalizeTranscript({
      words: [
        word("thank", 0, 0.3), space(0.3), word("you", 0.4, 0.6), space(0.6),
        { text: "applause", start: 0.7, end: 2, type: "audio_event" },
      ],
    });
    assert.equal(t.turns.length, 1);
    assert.equal(t.turns[0].text, "thank you (applause)");
    assert.equal(t.words, 2);
  });

  it("spaces words itself when the answer has no spacing entries", () => {
    const t = normalizeTranscript({ words: [word("a", 0, 0.1), word("b", 0.2, 0.3)] });
    assert.equal(t.turns[0].text, "a b");
  });

  it("reads a multichannel answer and an empty one", () => {
    const multi = normalizeTranscript({ transcripts: [{ words: [word("x", 0, 1)] }] });
    assert.equal(multi.turns.length, 1);
    const empty = normalizeTranscript({ text: "", words: [] });
    assert.equal(empty.words, 0);
    assert.equal(empty.turns.length, 0);
    assert.equal(normalizeTranscript(null).words, 0);
  });

  it("prefers the reported duration, else the last turn's end", () => {
    assert.equal(normalizeTranscript({ audio_duration_secs: 3600.123, words: [word("a", 0, 1)] }).audioSecs, 3600.12);
    assert.equal(normalizeTranscript({ words: [word("a", 0, 1.5)] }).audioSecs, 1.5);
  });

  it("writes seconds in the model's copy and clock time for people", () => {
    assert.equal(clock(754), "12:34");
    assert.equal(clock(3754), "1:02:34");
    assert.equal(clock(5), "0:05");
    const text = transcriptForModel({ turns: [{ s: "S1", t0: 754.6, t1: 760, text: "hi" }] });
    assert.equal(text, "[754] S1: hi");
  });
});

describe("webhook signature", () => {
  const secret = "wsec_test_secret_value";
  const body = Buffer.from(JSON.stringify({ type: "speech_to_text_transcription", data: { request_id: "r" } }));
  const now = 1_780_000_000_000;
  const t = Math.floor(now / 1000);
  const sign = (ts, key = secret, raw = body) =>
    createHmac("sha256", key).update(`${ts}.`).update(raw).digest("hex");

  it("accepts a fresh, correct signature", () => {
    assert.equal(verifySignature(body, `t=${t},v0=${sign(t)}`, secret, now), true);
  });
  it("accepts any one of several signatures, as during a secret rotation", () => {
    assert.equal(verifySignature(body, `t=${t},v0=${sign(t, "old")},v0=${sign(t)}`, secret, now), true);
  });
  it("refuses the wrong secret, a changed body, or no header", () => {
    assert.equal(verifySignature(body, `t=${t},v0=${sign(t, "nope")}`, secret, now), false);
    assert.equal(verifySignature(Buffer.from("{}"), `t=${t},v0=${sign(t)}`, secret, now), false);
    assert.equal(verifySignature(body, undefined, secret, now), false);
    assert.equal(verifySignature(body, `t=${t},v0=${sign(t)}`, "", now), false);
  });
  it("refuses a signature over half an hour old, or from the future", () => {
    const old = t - 31 * 60;
    assert.equal(verifySignature(body, `t=${old},v0=${sign(old)}`, secret, now), false);
    const future = t + 10 * 60;
    assert.equal(verifySignature(body, `t=${future},v0=${sign(future)}`, secret, now), false);
    const nearlyOld = t - 29 * 60;
    assert.equal(verifySignature(body, `t=${nearlyOld},v0=${sign(nearlyOld)}`, secret, now), true);
  });
  it("refuses malformed headers without throwing", () => {
    for (const header of ["", "garbage", `t=abc,v0=${sign(t)}`, `v0=${sign(t)}`, `t=${t}`, `t=${t},v0=zz`, `t=${t},v0=${sign(t).slice(2)}`]) {
      assert.equal(verifySignature(body, header, secret, now), false, header);
    }
  });

  it("finds our recording in the metadata, as an object or a string, and ignores anybody else's", () => {
    const id = "0f8fad5b-d9cb-469f-a165-70867728950e";
    const ours = (meta) => ({ data: { webhook_metadata: meta } });
    const app = "ai-notetaker";
    assert.deepEqual(metadataOf(ours({ app, ntRecordingId: id, run: 2 })), { recordingId: id, run: 2 });
    assert.deepEqual(metadataOf(ours(JSON.stringify({ app, ntRecordingId: id, run: 1 }))), { recordingId: id, run: 1 });
    assert.deepEqual(metadataOf(ours({ app, ntRecordingId: id, run: "3" })), { recordingId: id, run: 3 });
    assert.equal(metadataOf(ours({ recordingId: id, run: 1 })), null, "another app's metadata");
    assert.equal(metadataOf(ours({ app: "other", ntRecordingId: id, run: 1 })), null);
    assert.equal(metadataOf(ours({ app, ntRecordingId: "x", run: 1 })), null);
    assert.equal(metadataOf(ours({ app, ntRecordingId: id, run: 0 })), null);
    assert.equal(metadataOf({ data: {} }), null);
    assert.equal(metadataOf({ data: { webhook_metadata: "{broken" } }), null);
  });
});

describe("what counts as a transcript", () => {
  it("takes a body with words, even none, or a multichannel list of them", () => {
    assert.equal(isTranscript({ words: [] }), true);
    assert.equal(isTranscript({ text: "hi", words: [{ text: "hi" }] }), true);
    assert.equal(isTranscript({ transcripts: [{ words: [] }, { words: [] }] }), true);
  });
  it("refuses a status report, an error, or anything else", () => {
    for (const body of [null, undefined, "x", 3, {}, { status: "processing" }, { detail: "not ready" },
      { transcripts: [] }, { transcripts: [{ words: [] }, {}] }, { words: "none" }]) {
      assert.equal(isTranscript(body), false, JSON.stringify(body));
    }
  });
});

describe("summary prompt", () => {
  it("carries the meeting's details, pauses and the transcript", () => {
    const prompt = buildPrompt({
      recordedBy: "Meera Iyer",
      meetingAt: Date.UTC(2026, 8, 25, 6, 30),
      audioSecs: 124,
      title: "Weekly sync",
      location: "  Room 4 ",
      gaps: [{ startMs: 60_000, endMs: 240_000, reason: "call" }, { startMs: 1, endMs: null, reason: "killed" }],
      transcript: SAMPLE_TRANSCRIPT,
      language: "en",
    });
    assert.match(prompt.user, /Recorded by: Meera Iyer/);
    assert.match(prompt.user, /12:00\s*pm \(India time\)/i);
    assert.match(prompt.user, /Where: Room 4/);
    assert.match(prompt.user, /paused 1 time\(s\): 3 min \(a phone call\)/);
    assert.match(prompt.user, /<transcript>\n\[0\] S1: /);
    assert.match(prompt.user, /\[113\] S1: /);
    assert.match(prompt.system, /never instructions to you/);
    assert.match(prompt.system, /in English/);
  });

  it("is a schema both providers' strict modes accept: every object closed, every key required", () => {
    const walk = (node, path) => {
      if (!node || typeof node !== "object") return;
      if (node.type === "object") {
        assert.equal(node.additionalProperties, false, `${path} must be closed`);
        assert.deepEqual([...node.required].sort(), Object.keys(node.properties).sort(), `${path} must require every key`);
        for (const [k, v] of Object.entries(node.properties)) walk(v, `${path}.${k}`);
      }
      if (node.type === "array") walk(node.items, `${path}[]`);
      for (const bad of ["minimum", "maximum", "minLength", "maxLength", "pattern", "format"]) {
        assert.ok(!(bad in node), `${path} uses unsupported ${bad}`);
      }
    };
    walk(ANALYSIS_SCHEMA, "$");
  });
});

describe("reading the answer", () => {
  const minimal = { title: "T", summary: "S" };

  it("clamps enums, timestamps and lengths instead of refusing", () => {
    const a = normalizeAnalysis({
      ...minimal,
      mood: "ecstatic",
      meeting_kind: "Planning",
      key_points: [{ text: "k", at_seconds: 50 }, { text: "late", at_seconds: 500 }, { text: "none", at_seconds: -1 }, { text: "" }],
      action_items: [{ task: "do", owner: "G", due: "", priority: "urgent", raised_by: "S2", at_seconds: 39.7 }],
      participants: [{ name: "Harpreet", role: "", stance: "", note: "", speakers: ["S1", "speaker_0", 3] }],
      risks: ["r", 5, ""],
      summary: "S".repeat(10_000),
    }, 118);
    assert.equal(a.mood, "neutral");
    assert.equal(a.meeting_kind, "Planning");
    assert.deepEqual(a.key_points.map((k) => k.at_seconds), [50, null, null]);
    assert.equal(a.action_items[0].priority, "medium");
    assert.equal(a.action_items[0].at_seconds, 39);
    assert.deepEqual(a.participants[0].speakers, ["S1", "speaker_0"]);
    assert.deepEqual(a.risks, ["r"]);
    assert.equal(a.summary.length, 4000);
    assert.deepEqual(a.decisions, []);
  });

  it("maps older moods and keys onto the shape", () => {
    assert.equal(normalizeAnalysis({ ...minimal, mood: "very_positive" }, null).mood, "positive");
    assert.equal(normalizeAnalysis({ ...minimal, mood: "tense" }, null).mood, "mixed");
    assert.equal(normalizeAnalysis({ ...minimal, mood: "Mixed" }, null).mood, "mixed");
    const a = normalizeAnalysis({ ...minimal, questions: [{ question: "q?", status: "parked" }], howItWent: "fine" }, null);
    assert.equal(a.questions_raised[0].status, "open");
    assert.equal(a.how_it_went, "fine");
    assert.deepEqual(Object.keys(a), Object.keys(ANALYSIS_SCHEMA.properties));
  });

  it("refuses an answer with nothing in it", () => {
    assert.throws(() => normalizeAnalysis({}, null), AnalysisParseError);
    assert.throws(() => normalizeAnalysis([], null), AnalysisParseError);
    assert.throws(() => parseAnalysisText("no json here", null), AnalysisParseError);
    assert.throws(() => parseAnalysisText("{broken", null), AnalysisParseError);
  });

  it("reads JSON inside a stray code fence", () => {
    assert.equal(parseAnalysisText("```json\n{\"title\":\"x\",\"summary\":\"y\"}\n```", null).title, "x");
  });

  const message = (stop_reason, text, extra = {}) => ({
    stop_reason,
    content: [{ type: "thinking", thinking: "" }, { type: "text", text }],
    usage: { input_tokens: 1000, output_tokens: 200, cache_read_input_tokens: 10 },
    ...extra,
  });

  it("Claude: a finished answer, a cut-off one, a refusal", () => {
    const done = readMessage(message("end_turn", JSON.stringify(minimal)), null);
    assert.equal(done.kind, "done");
    assert.deepEqual(done.usage, { inputTokens: 1010, outputTokens: 200 });
    assert.equal(readMessage(message("max_tokens", "{\"title\":"), null).kind, "retry");
    const refused = readMessage(message("refusal", "", { stop_details: { category: "cyber" } }), null);
    assert.equal(refused.kind, "failed");
    assert.match(refused.message, /cyber/);
    assert.equal(readMessage(message("end_turn", "not json"), null).kind, "retry");
    assert.equal(readMessage(message("model_context_window_exceeded", ""), null).kind, "failed");
  });

  it("OpenAI: completed, incomplete, refused, failed", () => {
    const body = (status, content, extra = {}) => ({
      status,
      output: [{ type: "reasoning" }, { type: "message", content }],
      usage: { input_tokens: 900, output_tokens: 150 },
      ...extra,
    });
    const done = readResponse(body("completed", [{ type: "output_text", text: JSON.stringify(minimal) }]), null);
    assert.equal(done.kind, "done");
    assert.deepEqual(done.usage, { inputTokens: 900, outputTokens: 150 });
    assert.equal(readResponse(body("incomplete", [], { incomplete_details: { reason: "max_output_tokens" } }), null).kind, "retry");
    assert.equal(readResponse(body("incomplete", [], { incomplete_details: { reason: "content_filter" } }), null).kind, "failed");
    assert.equal(readResponse(body("completed", [{ type: "refusal", refusal: "no" }]), null).kind, "failed");
    assert.equal(readResponse(body("failed", [], { error: { message: "boom" } }), null).kind, "retry");
    assert.equal(readResponse(null, null).kind, "retry");
  });
});

describe("models", () => {
  it("defaults to Claude Sonnet 5.5 and falls back to it for anything unknown", () => {
    assert.equal(DEFAULT_MODEL_ID, "claude-sonnet-5-5");
    assert.equal(resolveModel("gpt-2").id, "claude-sonnet-5-5");
    assert.equal(resolveModel("gpt-6-sol").provider, "openai");
    assert.equal(new Set(SUMMARY_MODELS.map((m) => m.id)).size, SUMMARY_MODELS.length);
  });
  it("gives a cut-off summary twice the room, never past 64,000", () => {
    for (const m of SUMMARY_MODELS) {
      assert.equal(boostedMaxTokens(m), Math.min(64000, m.maxOutputTokens * 2));
      assert.ok(boostedMaxTokens(m) > m.maxOutputTokens || m.maxOutputTokens >= 64000);
    }
  });
  it("sends the model's usual limit, or the boosted one when asked", () => {
    const opus = resolveModel("claude-opus-5-5");
    const prompt = { system: "s", user: "u" };
    assert.equal(messageParams(opus, prompt).max_tokens, 32000);
    assert.equal(messageParams(opus, prompt, 64000).max_tokens, 64000);
    assert.match(messageParams(opus, prompt).system, /^s\n\nAnswer with one JSON object/);
    assert.match(messageParams(opus, prompt).system, /"key_points"/);
    assert.equal(messageParams(opus, prompt).output_config.effort, "medium");
    assert.equal("tool_choice" in messageParams(opus, prompt), false);
    assert.equal("output_config" in messageParams(resolveModel("claude-haiku-4-5"), prompt), false);
    const sol = resolveModel("gpt-6-sol");
    assert.equal(responseBody(sol, prompt).max_output_tokens, 32000);
    assert.equal(responseBody(sol, prompt, 64000).max_output_tokens, 64000);
    assert.equal(responseBody(sol, prompt).text.format.strict, true);
    assert.equal(responseBody(sol, prompt).store, false);
  });
  it("estimates cost", () => {
    const opus = resolveModel("claude-opus-5-5");
    assert.equal(estimateCostUsd(opus, { inputTokens: 50_000, outputTokens: 8_000 }), 0.36);
  });
});
