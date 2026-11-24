package com.humblesolutions.ainotetaker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.humblesolutions.ainotetaker.recorder.RecorderSync
import com.humblesolutions.ainotetaker.repository.ApiUserRepository
import com.humblesolutions.ainotetaker.ui.App
import com.humblesolutions.ainotetaker.util.LanguageStore
import com.humblesolutions.ainotetaker.util.PendingNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge, because the brand band runs under the status bar — the
        // screen is the colour, not a rectangle inside a grey frame.
        enableEdgeToEdge()
        // A tapped notification arrives here — the app may have been closed entirely.
        PendingNotification.offer(intent)
        forgetLegacyClaudeKey()
        // Whatever the recorder left unsent — or cut off — goes as soon as
        // there is signal. Cheap when there is nothing: one look at the queue.
        // replacing any run still waiting out a backoff from an earlier failure, which KEEP left in
        // place, so opening the app always uploads now. Only on a fresh start, not a rotation or a theme change, and
        // never a run that is uploading (see RecorderSync.now).
        if (savedInstanceState == null) RecorderSync.now(applicationContext, replace = true)
        setContent { App() }
    }

    /**
     * Deletes the Anthropic key that older builds kept in this app's
     * preferences.
     *
     * The notetaker used to ask every person to paste their own `sk-ant-…` in.
     * The field and the code that read it are gone, but an upgrade does not
     * empty a DataStore entry — so without this, a live credential would sit on
     * every handset that ever ran an older build, which is the exact leak that
     * moving the key to Secret Manager was meant to close.
     *
     * OFF THE MAIN THREAD AND NOT WAITED FOR, like the roll above: nothing on
     * screen depends on it, and it is idempotent, so a launch that is killed
     * before it finishes simply does it next time.
     */
    private fun forgetLegacyClaudeKey() {
        lifecycleScope.launch(Dispatchers.IO) {
            // a settings file that cannot be written is tried again next launch, never a crash.
            runCatching { LanguageStore(applicationContext).forgetLegacyClaudeKey() }
        }
    }

    /**
     * Back in the foreground: ask the CRM again who this is.
     *
     * TAS's Firestore listener heard a suspension the moment it
     * happened. The CRM is asked instead, and coming back to the app is when a
     * login turned off on the website should take effect. `onRestart`, not
     * `onStart`, because the first start already asks.
     */
    override fun onRestart() {
        super.onRestart()
        ApiUserRepository.refresh()
    }

    /**
     * A notification tap that arrives while the app is ALREADY RUNNING.
     *
     * `singleTask` means Android delivers it here instead of building a second
     * copy of the activity — which is the behaviour we want (one app, one
     * back stack) and the reason this override is not optional. Without it a
     * tap into a running app would silently do nothing.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        PendingNotification.offer(intent)
    }
}
