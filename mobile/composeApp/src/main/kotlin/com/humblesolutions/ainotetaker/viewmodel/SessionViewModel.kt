package com.humblesolutions.ainotetaker.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.ainotetaker.i18n.Language
import com.humblesolutions.ainotetaker.model.Appearance
import com.humblesolutions.ainotetaker.model.Session
import com.humblesolutions.ainotetaker.util.PendingNotification
import com.humblesolutions.ainotetaker.repository.AndroidAuthRepository
import com.humblesolutions.ainotetaker.repository.ApiUserRepository
import com.humblesolutions.ainotetaker.util.Cancellable
import com.humblesolutions.ainotetaker.usecase.ObserveSessionUseCase
import com.humblesolutions.ainotetaker.usecase.SignOutUseCase
import com.humblesolutions.ainotetaker.util.LanguageStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Who is signed in, for the whole app.
 *
 * ONE INSTANCE, scoped to the Activity, because every screen asks the same
 * question and a second subscription would ask the CRM twice for no gain.
 */
class SessionViewModel(application: Application) : AndroidViewModel(application) {

    // Manual DI — each view model builds its own chain, no framework.
    private val authRepository = AndroidAuthRepository()
    // who someone is comes from the CRM API (GET /api/me).
    private val userRepository = ApiUserRepository(application)
    private val observeSession = ObserveSessionUseCase(authRepository, userRepository)
    private val signOut = SignOutUseCase(authRepository)
    private val languageStore = LanguageStore(application)

    /**
     * How long the splash has been up this app run (`Startup.MIN_SHOW_MS` is
     * measured against it). Held HERE, not on the screen: Android rebuilds
     * the screen on every configuration change (the phone turned, light/dark
     * switched), and a screen-held count started over at zero — so the logo
     * flashed up again mid-use for its minimum time. This view model outlives
     * those rebuilds; everything else the gate waits for is judged as before.
     */
    var splashElapsedMs: Long = 0L

    private val _session = MutableStateFlow<Session>(Session.Loading)
    val session: StateFlow<Session> = _session.asStateFlow()

    // the app is English only. The string tables stay; nothing
    // offers another language.
    val language: StateFlow<Language> = MutableStateFlow(Language.ENGLISH).asStateFlow()

    private val _appearance = MutableStateFlow(Appearance.default)
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    private var subscription: Cancellable = observeSession.execute { _session.value = it }

    /**
     * Ask again, after the account could not be read.
     *
     * A RESUBSCRIBE, which asks the CRM again at once rather than waiting for
     * the repository's own next try. Tearing the chain down and rebuilding it
     * also re-runs the forced token refresh, which is the thing most likely
     * to have been missing.
     */
    fun onRetrySession() {
        subscription.cancel()
        _session.value = Session.Loading
        subscription = observeSession.execute { _session.value = it }
    }

    init {
        // a settings file that cannot be read is the default look, not a crash of the process the
        // recorder runs in.
        viewModelScope.launch {
            runCatching { languageStore.appearance.collect { _appearance.value = it } }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
    }

    fun onAppearanceChosen(appearance: Appearance) {
        _appearance.value = appearance
        viewModelScope.launch {
            runCatching { languageStore.setAppearance(appearance) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
    }

    fun onSignOut() {
        viewModelScope.launch {
            // A tap belongs to the session it was made in; see PendingNotification.
            PendingNotification.clear()
            runCatching { signOut.execute() }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
    }

    override fun onCleared() {
        subscription.cancel()
        super.onCleared()
    }
}
