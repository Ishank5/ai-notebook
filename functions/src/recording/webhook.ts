import { onRequest } from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";

import { ELEVENLABS_WEBHOOK_SECRET, REGION, describe } from "./common.js";
import { isTranscript, metadataOf, verifySignature, type WebhookPayload } from "./elevenlabs.js";
import { acceptTranscript, transcriptionFailed } from "./receive.js";

/**
 * Where ElevenLabs could post a finished transcript. NOT REGISTERED: the
 * pipeline polls instead (elevenlabs.ts). Kept because it is harmless: only
 * a signed body about one of our recordings (`app: "ai-notetaker"`) is read.
 *
 * PUBLIC, SO THE SIGNATURE IS THE LOCK. Anybody can reach this URL; only a
 * body signed with the secret in the last half hour is read at all. Everything
 * else — an unknown event, a recording that has moved on — is answered 200 and
 * ignored, because ElevenLabs switches a webhook off after enough failures and
 * none of those are failures of the webhook.
 */
export const ntElevenLabsWebhook = onRequest(
  {
    region: REGION,
    secrets: [ELEVENLABS_WEBHOOK_SECRET],
    invoker: "public",
    maxInstances: 10,
    concurrency: 20,
    memory: "512MiB",
    timeoutSeconds: 120,
  },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(405).send("POST only");
      return;
    }

    const raw = req.rawBody;
    const signature = req.get("elevenlabs-signature") ?? undefined;
    if (!raw || !verifySignature(raw, signature, ELEVENLABS_WEBHOOK_SECRET.value(), Date.now())) {
      logger.warn("ElevenLabs webhook refused: signature missing, wrong or old");
      res.status(401).send("invalid signature");
      return;
    }

    let payload: WebhookPayload;
    try {
      payload = JSON.parse(raw.toString("utf8")) as WebhookPayload;
    } catch {
      res.status(400).send("not JSON");
      return;
    }

    if (payload.type !== "speech_to_text_transcription") {
      res.status(200).send("ignored");
      return;
    }
    const meta = metadataOf(payload);
    if (!meta) {
      // Not ours: other apps' transcripts reach every webhook on the shared account.
      res.status(200).send("ignored");
      return;
    }

    try {
      const transcription = payload.data?.transcription;
      if (isTranscript(transcription)) {
        const outcome = await acceptTranscript(meta.recordingId, meta.run, transcription, "webhook");
        res.status(200).send(outcome);
        return;
      }
      /*
       * NO TRANSCRIPT. Only an explicit error fails the recording; anything
       * else is left to the poll.
       */
      const error = payload.data?.error;
      if (error !== undefined && error !== null && error !== "") {
        const message = typeof error === "string" ? error : JSON.stringify(error);
        await transcriptionFailed(meta.recordingId, meta.run, `ElevenLabs could not transcribe it: ${message.slice(0, 300)}`);
        res.status(200).send("failed");
        return;
      }
      logger.warn("Transcription webhook without a transcript or an error", { ...meta });
      res.status(200).send("ignored");
    } catch (err) {
      // Not retried by ElevenLabs; the poll recovers it.
      logger.error("Transcription webhook could not be stored", { ...meta, error: describe(err) });
      res.status(500).send("not stored");
    }
  },
);
