package com.humblesolutions.ainotetaker.i18n

/**
 * the AI Notebook name, the planned length, the warning before
 * a recording stops, and Instant start. English only, like the rest of the app;
 * the other languages fall back to it. Merged last, so these win over the
 * recorder's older strings and the generated tables.
 *
 * Placeholders: `{{time}}` a clock or a time of day, `{{left}}`, `{{to}}` and
 * `{{cap}}` lengths in words ("5 minutes", "3 hours").
 */
internal object NotetakerStrings {

    val en: Map<String, String> = mapOf(
        "common.nav.notetaker" to "AI Notebook",
        "recorder.title" to "AI Notebook",
        "recorder.limitNote" to "Asks before it stops. 3 hours at most.",
        "recorder.length" to "Length",
        "recorder.instant" to "Instant start",
        "recorder.stopsAt" to "Stops at {{time}}",
        "recorder.stopsIn" to "Stops in {{time}}",
        "recorder.keep" to "Keep recording",
        "recorder.stopNow" to "Stop now",

        "recorder.warn.title" to "Recording stops soon",
        "recorder.warn.body" to "Recording stops in {{left}}.",
        "recorder.warn.extend" to "Keep recording goes on to {{to}}.",
        "recorder.warn.final" to "Recording stops in {{left}}. {{cap}} is the longest it can run.",
        "recorder.warn.ok" to "OK",

        "recorder.notif.channel" to "AI Notebook",
        "recorder.notif.alertsChannel" to "AI Notebook alerts",
        "recorder.notif.recording" to "AI Notebook is recording",
        "recorder.notif.paused" to "AI Notebook is paused",
        "recorder.notif.pausedCall" to "AI Notebook paused for a call",
        "recorder.notif.resuming" to "AI Notebook is resuming",
        "recorder.notif.recordingText" to "Stops at {{time}}",
        "recorder.notif.pausedText" to "Tap to open",
        "recorder.notif.limit" to "Recording stopped at {{cap}}, the longest it can run. It is saved and uploading.",
        "recorder.notif.planned" to "Recording stopped at the planned length. It is saved and uploading.",
        "recorder.notif.chunkLimit" to "Recording stopped after too many pauses. It is saved and uploading.",

        // What could still cut a meeting off, as chips on the record card.
        "recorder.chip.battery" to "Allow background use",
        "recorder.chip.alerts" to "Alerts off",
        "recorder.hint.oneplus" to "OnePlus: Battery usage > Allow background activity, and lock the app in recents.",

        // A recording that ended without the person: the dead man's alarm, the boot receiver, or the service itself.
        "recorder.notif.cutOff" to "Recording stopped at {{time}}. Tap to continue.",
        "recorder.notif.micLost" to "Recording paused: microphone in use",
        "recorder.notif.micSilent" to "Microphone is silent. Is another app using it?",
        "recorder.notif.lowSpace" to "The phone is almost full. Free some space so the recording can go on.",
        "recorder.notif.stuck" to "Recording is not responding. Open the app to check it.",
        "recorder.notif.pausedResume" to "Recording paused. Tap to resume.",
        "recorder.notif.stillPaused" to "Recording is still paused. Tap Resume to carry on.",

        // The Live Activity's Stop opens the app, which asks first.
        "recorder.confirmStop.title" to "Stop recording?",
        "recorder.confirmStop.cancel" to "Cancel",

        // The Live Activity, once the app is no longer there to update it.
        "recorder.live.stale" to "Not recording. Open the app.",
        "recorder.live.call" to "Paused for a call",

        // The server takes nothing more for a recording deleted in the CRM; its audio stays on the phone 30 days.
        "recorder.status.deleted" to "Deleted by an admin",
        "recorder.err.alreadyFinished" to "Deleted by an admin. The audio stays on this phone for 30 days.",

        // Other people's recordings on this phone, which go when they sign in here again.
        "recorder.othersWaiting_one" to "1 meeting waiting for {{name}} to sign in",
        "recorder.othersWaiting_other" to "{{count}} meetings waiting for {{name}} to sign in",
        "recorder.othersWaiting.someone" to "another account",

        "recorder.err.chunkTooLarge" to "A minute of this recording was too large to send. It is kept on this phone.",
        "recorder.err.chunkDropped" to "A minute of this recording could not be sent.",
        "recorder.err.limitReached" to "This recording has reached 3 hours, the longest it can run.",
        "recorder.err.chunkLimit" to "Recording stopped after too many pauses. It is saved and will be sent.",
    )
}
