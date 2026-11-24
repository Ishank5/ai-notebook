/**
 * The presentation features' pure rules: note types, talk time, task sync and due dates, the new summary fields.
 *
 *   npm test     (builds first)
 */
import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { noteTypeFromHint, talkTimeOf } from "../lib/recording/notetype.js";
import { buildPrompt, normalizeAnalysis } from "../lib/recording/summary/prompt.js";
import { dueDateOf, planTaskSync } from "../lib/recording/tasks.js";
import { SAMPLE_TRANSCRIPT } from "../lib/recording/sample.js";

describe("note types", () => {
  it("lets a clear phone hint decide, and the model pick otherwise", () => {
    assert.equal(noteTypeFromHint("class"), "lecture");
    assert.equal(noteTypeFromHint("Voice note"), "voice_note");
    assert.equal(noteTypeFromHint("one-on-one"), "meeting");
    assert.equal(noteTypeFromHint("interview"), "meeting");
    assert.equal(noteTypeFromHint("other"), null);
    assert.equal(noteTypeFromHint(""), null);
    const base = { recordedBy: "A", meetingAt: null, audioSecs: 60, transcript: SAMPLE_TRANSCRIPT, language: "en" };
    assert.match(buildPrompt({ ...base, noteType: "lecture" }).system, /set note_type to "lecture"/);
    assert.doesNotMatch(buildPrompt({ ...base, noteType: "lecture" }).system, /voice note \(note_type/);
    assert.match(buildPrompt(base).system, /First decide the type/);
  });

  it("reads the new fields loosely, with safe defaults", () => {
    const a = normalizeAnalysis({
      title: "Entropy", summary: "S", note_type: "Lecture", topics: ["Entropy", "entropy", "Heat", "", 4],
      quiz: [
        { question: "Q1", options: ["a", "b", "c", "d", "e"], answer_index: 2, explanation: "x" },
        { question: "Q2", options: ["a", "b"], answer_index: 5 },
      ],
      mind_map: { branches: ["Heat", { label: "Laws", children: [{ label: "Second", children: [{ label: "Deep", children: [{ label: "Too deep" }] }] }] }] },
    }, null);
    assert.equal(a.note_type, "lecture");
    assert.deepEqual(a.topics, ["Entropy", "Heat"]);
    assert.equal(a.quiz.length, 1);
    assert.equal(a.quiz[0].options.length, 4);
    assert.equal(a.mind_map.center, "Entropy");
    assert.deepEqual(a.mind_map.branches[1].children[0].children[0], { label: "Deep", children: [] });
    assert.equal(normalizeAnalysis({ title: "T" }, null).note_type, "meeting");
    assert.deepEqual(normalizeAnalysis({ title: "T" }, null).mind_map, { center: "", branches: [] });
  });
});

describe("talk time", () => {
  it("adds up each speaker's turns, largest first", () => {
    const turns = [
      { s: "S1", t0: 0, t1: 10, text: "a" },
      { s: "S2", t0: 10, t1: 40, text: "b" },
      { s: "S1", t0: 40, t1: 45.6, text: "c" },
      { s: "?", t0: 45, t1: 50, text: "(noise)" },
    ];
    assert.deepEqual(talkTimeOf({ turns }), [{ speaker: "S2", seconds: 30 }, { speaker: "S1", seconds: 16 }]);
    assert.deepEqual(talkTimeOf(null), []);
  });
});

describe("tasks", () => {
  it("removes open AI tasks of an older primary, keeps done and manual ones, creates only what is new", () => {
    const plan = planTaskSync([
      { id: "r_old_0", source: "ai", summaryId: "old", done: false },
      { id: "r_old_1", source: "ai", summaryId: "old", done: true },
      { id: "m1", source: "manual", summaryId: null, done: false },
      { id: "r_new_0", source: "ai", summaryId: "new", done: true },
    ], ["r_new_0", "r_new_1"], "new");
    assert.deepEqual(plan.remove, ["r_old_0"]);
    assert.deepEqual(plan.create, ["r_new_1"]);
  });

  it("reads clear due dates relative to the meeting day in India, and nothing vague", () => {
    // Wednesday 25 November 2026, 10:00 in India.
    const meeting = Date.UTC(2026, 10, 25, 4, 30);
    assert.equal(dueDateOf("by Friday", meeting), "2026-11-27");
    assert.equal(dueDateOf("tomorrow", meeting), "2026-11-26");
    assert.equal(dueDateOf("day after tomorrow", meeting), "2026-11-27");
    assert.equal(dueDateOf("next week", meeting), "2026-11-30");
    assert.equal(dueDateOf("in two weeks", meeting), "2026-12-09");
    assert.equal(dueDateOf("end of the month", meeting), "2026-11-30");
    assert.equal(dueDateOf("3rd December", meeting), "2026-12-03");
    assert.equal(dueDateOf("Jan 5", meeting), "2027-01-05");
    assert.equal(dueDateOf("10/12", meeting), "2026-12-10");
    assert.equal(dueDateOf("2026-12-01", meeting), "2026-12-01");
    assert.equal(dueDateOf("soon", meeting), null);
    assert.equal(dueDateOf("", meeting), null);
  });
});
