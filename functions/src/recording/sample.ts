import type { Transcript } from "./transcript.js";

/**
 * A short, invented team meeting for the settings page's Test button: a decision, action items with owners and a
 * date, an open question, a risk and a little Hindi. Every name in it is made up.
 */
export const SAMPLE_TRANSCRIPT: Transcript = {
  version: 1,
  language: "eng",
  languageProbability: 0.9,
  audioSecs: 124,
  speakers: [
    { label: "S1", rawId: "speaker_0", words: 130, seconds: 62 },
    { label: "S2", rawId: "speaker_1", words: 60, seconds: 30 },
    { label: "S3", rawId: "speaker_2", words: 55, seconds: 28 },
  ],
  words: 245,
  turns: [
    { s: "S1", t0: 0, t1: 11, text: "Okay, let's start. I'm Meera. Today is the weekly sync for the campus events app. Two things: the launch date and the login bug." },
    { s: "S2", t0: 12, t1: 25, text: "Login first. On older Android phones the OTP screen freezes. About one in twenty testers hit it last week." },
    { s: "S1", t0: 26, t1: 33, text: "Arjun, can you take that? We need a fix before launch." },
    { s: "S2", t0: 34, t1: 40, text: "Yes, I'll have a fix by Thursday and send a test build to everyone." },
    { s: "S3", t0: 41, t1: 54, text: "On the launch, the fest starts on the 18th. If we launch on the 15th we get three days of sign-ups before it. Bas, that's enough time." },
    { s: "S1", t0: 55, t1: 63, text: "Agreed. Let's lock the 15th. Kavya, you'll handle the posters and the Instagram post?" },
    { s: "S3", t0: 64, t1: 70, text: "Haan, I'll have drafts ready by Monday for everyone to review." },
    { s: "S2", t0: 71, t1: 82, text: "One risk: the free hosting plan may not handle the traffic on the first day. We had a slowdown at two hundred users in testing." },
    { s: "S1", t0: 83, t1: 95, text: "Good point. Do we know what the paid plan costs? I'll ask the club treasurer whether there is budget for one month." },
    { s: "S3", t0: 96, t1: 103, text: "Also, are we adding the feedback form in version one, or later?" },
    { s: "S1", t0: 104, t1: 112, text: "Let's decide that next week once we see how the login fix goes. (laughter) One fire at a time." },
    { s: "S1", t0: 113, t1: 124, text: "Same time next Wednesday. Thanks everyone, good meeting." },
  ],
};

/** Who "recorded" the sample, and its title, for the prompt. */
export const SAMPLE_RECORDED_BY = "Meera Iyer";
export const SAMPLE_TITLE = "Events app weekly sync";
