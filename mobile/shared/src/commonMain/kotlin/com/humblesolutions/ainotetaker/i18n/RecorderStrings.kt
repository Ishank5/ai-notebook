package com.humblesolutions.ainotetaker.i18n

/**
 * The meeting recorder, in all three languages.
 *
 * A separate table for the same reason as [EventStrings]: [StringTables] is
 * generated from the web prototype and overwritten whenever that runs, and the
 * recorder has no source there. The meeting kinds are not repeated here — the
 * generated `notetaker.kind.*` labels are the same words.
 *
 * WRITTEN FOR SOMEBODY WITH A PHONE IN THEIR POCKET AT A MEETING. Every status
 * answers one question — is my meeting safe? — before it says anything else.
 *
 * Hindi and Punjabi are a careful attempt and want a native reader's eye
 * before release.
 *
 * strings that changed for the AI Notebook name and the planned
 * length (the title, the limit note, the notifications, the 3-hour limit) moved
 * to [NotetakerStrings], English only, so no translation here goes stale. The
 * other languages fall back to the English.
 */
internal object RecorderStrings {

    val en: Map<String, String> = mapOf(
        "recorder.eyebrow" to "RECORDER",
        "recorder.description" to "The phone records the meeting and keeps it safe, with or without signal. It is sent by itself when there is a connection.",
        "recorder.record" to "Start recording",
        "recorder.starting" to "Starting…",
        "recorder.recording" to "Recording",
        "recorder.paused" to "Paused",
        "recorder.pausedCall" to "Paused for a phone call. It carries on when the call ends",
        "recorder.pausedInterruption" to "Paused: another app is using the microphone",
        "recorder.pause" to "Pause",
        "recorder.resume" to "Resume",
        "recorder.stop" to "Stop",
        "recorder.stopping" to "Saving…",
        "recorder.lockedOk" to "You can lock the phone. Recording carries on.",

        "recorder.interrupted.title" to "A recording was cut off",
        "recorder.interrupted.body" to "The recording started at {{time}} stopped when the app was closed or the phone switched off. {{duration}} was saved.",
        "recorder.interrupted.continue" to "Continue recording",
        "recorder.interrupted.finish" to "Finish and send",
        "recorder.interrupted.auto" to "If you do nothing, it is sent as it is after an hour.",

        "recorder.details.title" to "About this meeting",
        "recorder.details.hint" to "Optional. It helps whoever reads the summary.",
        "recorder.details.fieldTitle" to "Title",
        "recorder.details.fieldKind" to "Type of note",
        "recorder.details.fieldWhere" to "Where",
        "recorder.details.fieldWho" to "Who was there",
        "recorder.details.save" to "Save details",
        "recorder.details.skip" to "Skip",
        "recorder.details.saved" to "Details saved",
        "recorder.details.edit" to "Add details",

        "recorder.list.title" to "Your recordings",
        "recorder.list.empty" to "No recordings yet.",
        "recorder.list.untitled" to "Meeting on {{date}}",
        "recorder.status.live" to "Recording now",
        "recorder.status.interrupted" to "Cut off, needs you",
        "recorder.status.waiting" to "Waiting for signal · {{sent}} of {{total}} sent",
        "recorder.status.sending" to "Sending · {{sent}} of {{total}}",
        "recorder.status.checking" to "Checking…",
        "recorder.status.sent" to "Sent",
        "recorder.status.summaryReady" to "Summary ready",
        "recorder.status.failed" to "Not sent",
        "recorder.sendNow" to "Send now",

        "recorder.reliability.title" to "Keep recordings safe",
        "recorder.reliability.body" to "Some phones stop apps running in the background to save battery. Allow AI Notebook to run in the background so a long meeting is never cut off.",
        "recorder.reliability.open" to "Open battery settings",
        "recorder.reliability.dismiss" to "Not now",
        "recorder.openSettings" to "Open settings",

        "recorder.notif.stoppedError" to "Recording stopped. What was recorded is saved and will be sent.",
        "recorder.notif.interrupted" to "A recording was cut off. Open AI Notebook to continue it or send it as it is.",

        "recorder.err.signedOut" to "Sign in to send your recordings. They are safe on this phone.",
        "recorder.err.inactive" to "Your account is not active, so recordings cannot be sent. Ask your admin.",
        "recorder.err.badRequest" to "This recording could not be sent. It is kept on this phone.",
        "recorder.err.notYours" to "This recording belongs to another account and cannot be sent from here.",
        "recorder.err.alreadyFinished" to "Part of this recording arrived after it was sent and was left out.",
        "recorder.err.server" to "The server could not take recordings just now. It will try again.",
        "recorder.err.nothingUploaded" to "Sending again from the start.",
        "recorder.err.tooLarge" to "This recording is too large to send.",
        "recorder.err.accountChanged" to "Someone else is signed in. The recording is sent when the person who made it signs in again.",
        "recorder.err.network" to "No signal. It will be sent when there is.",
        "recorder.err.chunkDropped" to "A minute of this recording could not be read and was left out.",
        "recorder.err.alreadyRecording" to "A recording is already running.",
        "recorder.err.noSpace" to "The phone is nearly full. Free up some space to record.",
        "recorder.err.notFound" to "That recording is no longer on this phone.",
        "recorder.err.cannotContinue" to "That recording cannot be continued.",
        "recorder.err.micPermission" to "The microphone is not allowed. Turn it on for AI Notebook in your phone's settings.",
        "recorder.err.micBusy" to "The microphone could not be opened. Close any other app using it and try again.",
        "recorder.err.startFailed" to "Recording could not start. Try again.",
        "recorder.err.storage" to "The recording could not be saved on this phone.",
    )

    val hi: Map<String, String> = mapOf(
        "recorder.eyebrow" to "रिकॉर्डर",
        "recorder.description" to "फ़ोन बैठक को रिकॉर्ड करके सुरक्षित रखता है, सिग्नल हो या न हो। कनेक्शन मिलते ही यह अपने आप भेज दी जाती है।",
        "recorder.record" to "रिकॉर्डिंग शुरू करें",
        "recorder.starting" to "शुरू हो रही है…",
        "recorder.recording" to "रिकॉर्डिंग जारी है",
        "recorder.paused" to "रुकी हुई है",
        "recorder.pausedCall" to "फ़ोन कॉल के लिए रुकी है। कॉल खत्म होते ही फिर शुरू होगी",
        "recorder.pausedInterruption" to "रुकी है: कोई दूसरा ऐप माइक्रोफ़ोन इस्तेमाल कर रहा है",
        "recorder.pause" to "रोकें",
        "recorder.resume" to "फिर शुरू करें",
        "recorder.stop" to "बंद करें",
        "recorder.stopping" to "सहेजी जा रही है…",
        "recorder.lockedOk" to "आप फ़ोन लॉक कर सकते हैं। रिकॉर्डिंग चलती रहेगी।",

        "recorder.interrupted.title" to "एक रिकॉर्डिंग बीच में कट गई",
        "recorder.interrupted.body" to "{{time}} बजे शुरू हुई रिकॉर्डिंग ऐप बंद होने या फ़ोन बंद होने से रुक गई। {{duration}} की रिकॉर्डिंग सहेजी गई है।",
        "recorder.interrupted.continue" to "रिकॉर्डिंग जारी रखें",
        "recorder.interrupted.finish" to "खत्म करें और भेजें",
        "recorder.interrupted.auto" to "अगर आप कुछ नहीं करते, तो एक घंटे बाद यह जैसी है वैसी भेज दी जाएगी।",

        "recorder.details.title" to "इस बैठक के बारे में",
        "recorder.details.hint" to "वैकल्पिक। इससे सारांश पढ़ने वाले को मदद मिलती है।",
        "recorder.details.fieldTitle" to "शीर्षक",
        "recorder.details.fieldKind" to "बैठक का प्रकार",
        "recorder.details.fieldWhere" to "कहाँ",
        "recorder.details.fieldWho" to "कौन-कौन था",
        "recorder.details.save" to "विवरण सहेजें",
        "recorder.details.skip" to "छोड़ें",
        "recorder.details.saved" to "विवरण सहेजा गया",
        "recorder.details.edit" to "विवरण जोड़ें",

        "recorder.list.title" to "आपकी रिकॉर्डिंग",
        "recorder.list.empty" to "अभी कोई रिकॉर्डिंग नहीं है।",
        "recorder.list.untitled" to "{{date}} की बैठक",
        "recorder.status.live" to "अभी रिकॉर्ड हो रही है",
        "recorder.status.interrupted" to "कट गई, आपकी ज़रूरत है",
        "recorder.status.waiting" to "सिग्नल का इंतज़ार · {{total}} में से {{sent}} भेजे गए",
        "recorder.status.sending" to "भेजी जा रही है · {{total}} में से {{sent}}",
        "recorder.status.checking" to "जाँच हो रही है…",
        "recorder.status.sent" to "भेज दी गई",
        "recorder.status.summaryReady" to "सारांश तैयार",
        "recorder.status.failed" to "नहीं भेजी जा सकी",
        "recorder.sendNow" to "अभी भेजें",

        "recorder.reliability.title" to "रिकॉर्डिंग सुरक्षित रखें",
        "recorder.reliability.body" to "कुछ फ़ोन बैटरी बचाने के लिए पीछे चल रहे ऐप बंद कर देते हैं। AI Notebook को बैकग्राउंड में चलने की अनुमति दें ताकि लंबी बैठक कभी बीच में न कटे।",
        "recorder.reliability.open" to "बैटरी सेटिंग्स खोलें",
        "recorder.reliability.dismiss" to "अभी नहीं",
        "recorder.openSettings" to "सेटिंग्स खोलें",

        "recorder.notif.stoppedError" to "रिकॉर्डिंग रुक गई। जितनी रिकॉर्ड हुई वह सहेज ली गई है और भेज दी जाएगी।",
        "recorder.notif.interrupted" to "एक रिकॉर्डिंग बीच में कट गई। इसे जारी रखने या जैसी है वैसी भेजने के लिए AI Notebook खोलें।",

        "recorder.err.signedOut" to "अपनी रिकॉर्डिंग भेजने के लिए साइन इन करें। वे इस फ़ोन पर सुरक्षित हैं।",
        "recorder.err.inactive" to "आपका खाता सक्रिय नहीं है, इसलिए रिकॉर्डिंग नहीं भेजी जा सकती। अपने CRM एडमिन से पूछें।",
        "recorder.err.badRequest" to "यह रिकॉर्डिंग भेजी नहीं जा सकी। यह इस फ़ोन पर रखी है।",
        "recorder.err.notYours" to "यह रिकॉर्डिंग किसी दूसरे खाते की है और यहाँ से नहीं भेजी जा सकती।",
        "recorder.err.alreadyFinished" to "इस रिकॉर्डिंग का कुछ हिस्सा भेजे जाने के बाद पहुँचा और छूट गया।",
        "recorder.err.server" to "सर्वर अभी रिकॉर्डिंग नहीं ले सका। यह दोबारा कोशिश करेगा।",
        "recorder.err.nothingUploaded" to "शुरू से दोबारा भेजी जा रही है।",
        "recorder.err.tooLarge" to "यह रिकॉर्डिंग भेजने के लिए बहुत बड़ी है।",
        "recorder.err.accountChanged" to "कोई और साइन इन है। रिकॉर्ड करने वाले व्यक्ति के दोबारा साइन इन करने पर रिकॉर्डिंग भेज दी जाएगी।",
        "recorder.err.network" to "सिग्नल नहीं है। सिग्नल मिलते ही भेज दी जाएगी।",
        "recorder.err.chunkDropped" to "इस रिकॉर्डिंग का एक मिनट पढ़ा नहीं जा सका और छोड़ दिया गया।",
        "recorder.err.alreadyRecording" to "एक रिकॉर्डिंग पहले से चल रही है।",
        "recorder.err.noSpace" to "फ़ोन लगभग भर गया है। रिकॉर्ड करने के लिए कुछ जगह खाली करें।",
        "recorder.err.notFound" to "वह रिकॉर्डिंग अब इस फ़ोन पर नहीं है।",
        "recorder.err.cannotContinue" to "वह रिकॉर्डिंग जारी नहीं रखी जा सकती।",
        "recorder.err.micPermission" to "माइक्रोफ़ोन की अनुमति नहीं है। फ़ोन की सेटिंग्स में AI Notebook के लिए इसे चालू करें।",
        "recorder.err.micBusy" to "माइक्रोफ़ोन नहीं खुल सका। इसे इस्तेमाल कर रहा कोई दूसरा ऐप बंद करके दोबारा कोशिश करें।",
        "recorder.err.startFailed" to "रिकॉर्डिंग शुरू नहीं हो सकी। दोबारा कोशिश करें।",
        "recorder.err.storage" to "रिकॉर्डिंग इस फ़ोन पर सहेजी नहीं जा सकी।",
    )

    val pa: Map<String, String> = mapOf(
        "recorder.eyebrow" to "ਰਿਕਾਰਡਰ",
        "recorder.description" to "ਫ਼ੋਨ ਮੀਟਿੰਗ ਨੂੰ ਰਿਕਾਰਡ ਕਰਕੇ ਸੁਰੱਖਿਅਤ ਰੱਖਦਾ ਹੈ, ਸਿਗਨਲ ਹੋਵੇ ਜਾਂ ਨਾ। ਕਨੈਕਸ਼ਨ ਮਿਲਦੇ ਹੀ ਇਹ ਆਪਣੇ-ਆਪ ਭੇਜ ਦਿੱਤੀ ਜਾਂਦੀ ਹੈ।",
        "recorder.record" to "ਰਿਕਾਰਡਿੰਗ ਸ਼ੁਰੂ ਕਰੋ",
        "recorder.starting" to "ਸ਼ੁਰੂ ਹੋ ਰਹੀ ਹੈ…",
        "recorder.recording" to "ਰਿਕਾਰਡਿੰਗ ਚੱਲ ਰਹੀ ਹੈ",
        "recorder.paused" to "ਰੁਕੀ ਹੋਈ ਹੈ",
        "recorder.pausedCall" to "ਫ਼ੋਨ ਕਾਲ ਲਈ ਰੁਕੀ ਹੈ। ਕਾਲ ਖ਼ਤਮ ਹੁੰਦੇ ਹੀ ਮੁੜ ਸ਼ੁਰੂ ਹੋਵੇਗੀ",
        "recorder.pausedInterruption" to "ਰੁਕੀ ਹੈ: ਕੋਈ ਹੋਰ ਐਪ ਮਾਈਕ੍ਰੋਫ਼ੋਨ ਵਰਤ ਰਹੀ ਹੈ",
        "recorder.pause" to "ਰੋਕੋ",
        "recorder.resume" to "ਮੁੜ ਸ਼ੁਰੂ ਕਰੋ",
        "recorder.stop" to "ਬੰਦ ਕਰੋ",
        "recorder.stopping" to "ਸੰਭਾਲੀ ਜਾ ਰਹੀ ਹੈ…",
        "recorder.lockedOk" to "ਤੁਸੀਂ ਫ਼ੋਨ ਲਾਕ ਕਰ ਸਕਦੇ ਹੋ। ਰਿਕਾਰਡਿੰਗ ਚੱਲਦੀ ਰਹੇਗੀ।",

        "recorder.interrupted.title" to "ਇੱਕ ਰਿਕਾਰਡਿੰਗ ਵਿਚਾਲੇ ਕੱਟੀ ਗਈ",
        "recorder.interrupted.body" to "{{time}} ਵਜੇ ਸ਼ੁਰੂ ਹੋਈ ਰਿਕਾਰਡਿੰਗ ਐਪ ਬੰਦ ਹੋਣ ਜਾਂ ਫ਼ੋਨ ਬੰਦ ਹੋਣ ਨਾਲ ਰੁਕ ਗਈ। {{duration}} ਦੀ ਰਿਕਾਰਡਿੰਗ ਸੰਭਾਲੀ ਗਈ ਹੈ।",
        "recorder.interrupted.continue" to "ਰਿਕਾਰਡਿੰਗ ਜਾਰੀ ਰੱਖੋ",
        "recorder.interrupted.finish" to "ਖ਼ਤਮ ਕਰੋ ਅਤੇ ਭੇਜੋ",
        "recorder.interrupted.auto" to "ਜੇ ਤੁਸੀਂ ਕੁਝ ਨਹੀਂ ਕਰਦੇ, ਤਾਂ ਇੱਕ ਘੰਟੇ ਬਾਅਦ ਇਹ ਜਿਵੇਂ ਹੈ ਉਵੇਂ ਭੇਜ ਦਿੱਤੀ ਜਾਵੇਗੀ।",

        "recorder.details.title" to "ਇਸ ਮੀਟਿੰਗ ਬਾਰੇ",
        "recorder.details.hint" to "ਵਿਕਲਪਿਕ। ਇਸ ਨਾਲ ਸਾਰ ਪੜ੍ਹਨ ਵਾਲੇ ਦੀ ਮਦਦ ਹੁੰਦੀ ਹੈ।",
        "recorder.details.fieldTitle" to "ਸਿਰਲੇਖ",
        "recorder.details.fieldKind" to "ਮੀਟਿੰਗ ਦੀ ਕਿਸਮ",
        "recorder.details.fieldWhere" to "ਕਿੱਥੇ",
        "recorder.details.fieldWho" to "ਕੌਣ-ਕੌਣ ਸੀ",
        "recorder.details.save" to "ਵੇਰਵੇ ਸੰਭਾਲੋ",
        "recorder.details.skip" to "ਛੱਡੋ",
        "recorder.details.saved" to "ਵੇਰਵੇ ਸੰਭਾਲੇ ਗਏ",
        "recorder.details.edit" to "ਵੇਰਵੇ ਜੋੜੋ",

        "recorder.list.title" to "ਤੁਹਾਡੀਆਂ ਰਿਕਾਰਡਿੰਗਾਂ",
        "recorder.list.empty" to "ਹਾਲੇ ਕੋਈ ਰਿਕਾਰਡਿੰਗ ਨਹੀਂ।",
        "recorder.list.untitled" to "{{date}} ਦੀ ਮੀਟਿੰਗ",
        "recorder.status.live" to "ਹੁਣ ਰਿਕਾਰਡ ਹੋ ਰਹੀ ਹੈ",
        "recorder.status.interrupted" to "ਕੱਟੀ ਗਈ, ਤੁਹਾਡੀ ਲੋੜ ਹੈ",
        "recorder.status.waiting" to "ਸਿਗਨਲ ਦੀ ਉਡੀਕ · {{total}} ਵਿੱਚੋਂ {{sent}} ਭੇਜੇ",
        "recorder.status.sending" to "ਭੇਜੀ ਜਾ ਰਹੀ ਹੈ · {{total}} ਵਿੱਚੋਂ {{sent}}",
        "recorder.status.checking" to "ਜਾਂਚ ਹੋ ਰਹੀ ਹੈ…",
        "recorder.status.sent" to "ਭੇਜ ਦਿੱਤੀ ਗਈ",
        "recorder.status.summaryReady" to "ਸਾਰ ਤਿਆਰ",
        "recorder.status.failed" to "ਭੇਜੀ ਨਹੀਂ ਜਾ ਸਕੀ",
        "recorder.sendNow" to "ਹੁਣੇ ਭੇਜੋ",

        "recorder.reliability.title" to "ਰਿਕਾਰਡਿੰਗਾਂ ਸੁਰੱਖਿਅਤ ਰੱਖੋ",
        "recorder.reliability.body" to "ਕੁਝ ਫ਼ੋਨ ਬੈਟਰੀ ਬਚਾਉਣ ਲਈ ਪਿੱਛੇ ਚੱਲ ਰਹੀਆਂ ਐਪਾਂ ਬੰਦ ਕਰ ਦਿੰਦੇ ਹਨ। AI Notebook ਨੂੰ ਬੈਕਗ੍ਰਾਊਂਡ ਵਿੱਚ ਚੱਲਣ ਦੀ ਇਜਾਜ਼ਤ ਦਿਓ ਤਾਂ ਜੋ ਲੰਬੀ ਮੀਟਿੰਗ ਕਦੇ ਵਿਚਾਲੇ ਨਾ ਕੱਟੇ।",
        "recorder.reliability.open" to "ਬੈਟਰੀ ਸੈਟਿੰਗਾਂ ਖੋਲ੍ਹੋ",
        "recorder.reliability.dismiss" to "ਹੁਣ ਨਹੀਂ",
        "recorder.openSettings" to "ਸੈਟਿੰਗਾਂ ਖੋਲ੍ਹੋ",

        "recorder.notif.stoppedError" to "ਰਿਕਾਰਡਿੰਗ ਰੁਕ ਗਈ। ਜਿੰਨੀ ਰਿਕਾਰਡ ਹੋਈ ਉਹ ਸੰਭਾਲ ਲਈ ਗਈ ਹੈ ਅਤੇ ਭੇਜ ਦਿੱਤੀ ਜਾਵੇਗੀ।",
        "recorder.notif.interrupted" to "ਇੱਕ ਰਿਕਾਰਡਿੰਗ ਵਿਚਾਲੇ ਕੱਟੀ ਗਈ। ਇਸ ਨੂੰ ਜਾਰੀ ਰੱਖਣ ਜਾਂ ਜਿਵੇਂ ਹੈ ਉਵੇਂ ਭੇਜਣ ਲਈ AI Notebook ਖੋਲ੍ਹੋ।",

        "recorder.err.signedOut" to "ਆਪਣੀਆਂ ਰਿਕਾਰਡਿੰਗਾਂ ਭੇਜਣ ਲਈ ਸਾਈਨ ਇਨ ਕਰੋ। ਉਹ ਇਸ ਫ਼ੋਨ 'ਤੇ ਸੁਰੱਖਿਅਤ ਹਨ।",
        "recorder.err.inactive" to "ਤੁਹਾਡਾ ਖਾਤਾ ਸਰਗਰਮ ਨਹੀਂ ਹੈ, ਇਸ ਲਈ ਰਿਕਾਰਡਿੰਗਾਂ ਭੇਜੀਆਂ ਨਹੀਂ ਜਾ ਸਕਦੀਆਂ। ਆਪਣੇ CRM ਐਡਮਿਨ ਨੂੰ ਪੁੱਛੋ।",
        "recorder.err.badRequest" to "ਇਹ ਰਿਕਾਰਡਿੰਗ ਭੇਜੀ ਨਹੀਂ ਜਾ ਸਕੀ। ਇਹ ਇਸ ਫ਼ੋਨ 'ਤੇ ਰੱਖੀ ਹੋਈ ਹੈ।",
        "recorder.err.notYours" to "ਇਹ ਰਿਕਾਰਡਿੰਗ ਕਿਸੇ ਹੋਰ ਖਾਤੇ ਦੀ ਹੈ ਅਤੇ ਇੱਥੋਂ ਭੇਜੀ ਨਹੀਂ ਜਾ ਸਕਦੀ।",
        "recorder.err.alreadyFinished" to "ਇਸ ਰਿਕਾਰਡਿੰਗ ਦਾ ਕੁਝ ਹਿੱਸਾ ਭੇਜਣ ਤੋਂ ਬਾਅਦ ਪਹੁੰਚਿਆ ਅਤੇ ਛੱਡ ਦਿੱਤਾ ਗਿਆ।",
        "recorder.err.server" to "ਸਰਵਰ ਹੁਣੇ ਰਿਕਾਰਡਿੰਗ ਨਹੀਂ ਲੈ ਸਕਿਆ। ਇਹ ਦੁਬਾਰਾ ਕੋਸ਼ਿਸ਼ ਕਰੇਗਾ।",
        "recorder.err.nothingUploaded" to "ਸ਼ੁਰੂ ਤੋਂ ਦੁਬਾਰਾ ਭੇਜੀ ਜਾ ਰਹੀ ਹੈ।",
        "recorder.err.tooLarge" to "ਇਹ ਰਿਕਾਰਡਿੰਗ ਭੇਜਣ ਲਈ ਬਹੁਤ ਵੱਡੀ ਹੈ।",
        "recorder.err.accountChanged" to "ਕੋਈ ਹੋਰ ਸਾਈਨ ਇਨ ਹੈ। ਰਿਕਾਰਡ ਕਰਨ ਵਾਲੇ ਵਿਅਕਤੀ ਦੇ ਦੁਬਾਰਾ ਸਾਈਨ ਇਨ ਕਰਨ 'ਤੇ ਰਿਕਾਰਡਿੰਗ ਭੇਜ ਦਿੱਤੀ ਜਾਵੇਗੀ।",
        "recorder.err.network" to "ਸਿਗਨਲ ਨਹੀਂ ਹੈ। ਸਿਗਨਲ ਮਿਲਦੇ ਹੀ ਭੇਜ ਦਿੱਤੀ ਜਾਵੇਗੀ।",
        "recorder.err.chunkDropped" to "ਇਸ ਰਿਕਾਰਡਿੰਗ ਦਾ ਇੱਕ ਮਿੰਟ ਪੜ੍ਹਿਆ ਨਹੀਂ ਜਾ ਸਕਿਆ ਅਤੇ ਛੱਡ ਦਿੱਤਾ ਗਿਆ।",
        "recorder.err.alreadyRecording" to "ਇੱਕ ਰਿਕਾਰਡਿੰਗ ਪਹਿਲਾਂ ਹੀ ਚੱਲ ਰਹੀ ਹੈ।",
        "recorder.err.noSpace" to "ਫ਼ੋਨ ਲਗਭਗ ਭਰ ਗਿਆ ਹੈ। ਰਿਕਾਰਡ ਕਰਨ ਲਈ ਕੁਝ ਥਾਂ ਖਾਲੀ ਕਰੋ।",
        "recorder.err.notFound" to "ਉਹ ਰਿਕਾਰਡਿੰਗ ਹੁਣ ਇਸ ਫ਼ੋਨ 'ਤੇ ਨਹੀਂ ਹੈ।",
        "recorder.err.cannotContinue" to "ਉਹ ਰਿਕਾਰਡਿੰਗ ਜਾਰੀ ਨਹੀਂ ਰੱਖੀ ਜਾ ਸਕਦੀ।",
        "recorder.err.micPermission" to "ਮਾਈਕ੍ਰੋਫ਼ੋਨ ਦੀ ਇਜਾਜ਼ਤ ਨਹੀਂ ਹੈ। ਫ਼ੋਨ ਦੀਆਂ ਸੈਟਿੰਗਾਂ ਵਿੱਚ AI Notebook ਲਈ ਇਸ ਨੂੰ ਚਾਲੂ ਕਰੋ।",
        "recorder.err.micBusy" to "ਮਾਈਕ੍ਰੋਫ਼ੋਨ ਖੁੱਲ੍ਹ ਨਹੀਂ ਸਕਿਆ। ਇਸ ਨੂੰ ਵਰਤ ਰਹੀ ਕੋਈ ਹੋਰ ਐਪ ਬੰਦ ਕਰਕੇ ਦੁਬਾਰਾ ਕੋਸ਼ਿਸ਼ ਕਰੋ।",
        "recorder.err.startFailed" to "ਰਿਕਾਰਡਿੰਗ ਸ਼ੁਰੂ ਨਹੀਂ ਹੋ ਸਕੀ। ਦੁਬਾਰਾ ਕੋਸ਼ਿਸ਼ ਕਰੋ।",
        "recorder.err.storage" to "ਰਿਕਾਰਡਿੰਗ ਇਸ ਫ਼ੋਨ 'ਤੇ ਸੰਭਾਲੀ ਨਹੀਂ ਜਾ ਸਕੀ।",
    )
}
