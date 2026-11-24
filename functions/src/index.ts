import { initializeApp } from "firebase-admin/app";

initializeApp();

/*
 * AI Notebook, codebase `ainotetaker`. Every name starts with `nt` so nothing collides with TAS's functions in
 * the same project. See recording/common.ts for how the pieces fit.
 */

/** The HTTP API for the phones and the admin panel. */
export { ntApi } from "./api.js";
/** Processing a recording: join, transcribe (polled), summarise. Cloud Tasks queue `ntProcessRecording`. */
export { ntProcessRecording } from "./recording/pipeline.js";
/** Every five minutes: the backstop for every step. */
export { ntRecordingSweep } from "./recording/sweep.js";
/** Not registered with ElevenLabs (the pipeline polls); harmless, signature checked. */
export { ntElevenLabsWebhook } from "./recording/webhook.js";
