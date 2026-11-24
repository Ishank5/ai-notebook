package com.humblesolutions.ainotetaker.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.ainotetaker.model.Credentials
import com.humblesolutions.ainotetaker.repository.AndroidAuthRepository
import com.humblesolutions.ainotetaker.usecase.SignInUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One screen, two boxes.
 *
 * WHAT THIS USED TO BE: a two-door state machine — pick email or phone, send a
 * code, count down a resend timer, handle Android auto-verifying without a
 * code at all, then redeem. Six states and a timer. A username and a password
 * is a single request, so the whole machine is a boolean and an error key.
 */
data class SignInUiState(
    val username: String = "",
    val password: String = "",
    val busy: Boolean = false,
    /** A string key, never a Firebase message — see [SignInUseCase]. */
    val errorKey: String? = null,
    /**
     * Whether this person has typed anything yet.
     *
     * The sign-in screen may arrive carrying a reason it was shown — suspended,
     * no longer registered. That notice is about the LAST session, so it stops
     * being true the moment somebody starts on the next one.
     */
    val touched: Boolean = false,
    /** True while the password is shown in the clear. Never survives the screen. */
    val passwordVisible: Boolean = false,
) {
    val canSubmit: Boolean get() = username.isNotBlank() && password.isNotBlank() && !busy
}

class SignInViewModel : ViewModel() {

    private val signIn = SignInUseCase(AndroidAuthRepository())

    private val _uiState = MutableStateFlow(SignInUiState())
    val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

    fun onUsername(value: String) = _uiState.update {
        /*
         * Sanitised as it is typed, because that is what the account IS —
         * correcting it silently at submit would show them one thing and use
         * another. `Credentials` owns the rule so the two platforms cannot
         * disagree about what a pasted username becomes.
         */
        it.copy(
            username = Credentials.sanitiseUsername(value),
            errorKey = null,
            touched = true,
        )
    }

    fun onPassword(value: String) = _uiState.update {
        it.copy(password = value, errorKey = null, touched = true)
    }

    fun onTogglePasswordVisible() = _uiState.update {
        it.copy(passwordVisible = !it.passwordVisible)
    }

    fun onSubmit() {
        /*
         * BUSY IS SET BEFORE THE COROUTINE STARTS, NOT INSIDE IT.
         *
         * `canSubmit` was the only guard and `busy` was raised inside
         * `launch`, so two taps a frame apart both passed the guard — the
         * second one read a state the first had not written yet — and the app
         * made two sign-in requests. On a rate-limited endpoint that is not
         * merely wasteful: it is half of everybody's allowance, spent on a
         * double tap.
         *
         * `getAndUpdate` makes the check and the claim one step, so only the
         * tap that actually flipped the flag proceeds.
         */
        val previous = _uiState.getAndUpdate {
            if (it.canSubmit) it.copy(busy = true, errorKey = null) else it
        }
        if (!previous.canSubmit) return

        viewModelScope.launch {
            val error = signIn.execute(previous.username, previous.password)
            /*
             * THE PASSWORD NEVER SURVIVES AN ATTEMPT, and on success neither
             * does the username.
             *
             * THIS VIEW MODEL OUTLIVES THE SCREEN. It is scoped to the
             * Activity, so the instance that signed somebody in is the same
             * one the sign-in screen gets when they sign out — and it was
             * still holding what they typed. On a shared or handed-down
             * handset, which this app is built for, the next person to open
             * it found the last person's username and password already in the
             * boxes.
             *
             * On a FAILURE the username stays: a wrong password re-typed is
             * the ordinary case, and making somebody re-enter a username they
             * got right is a tax on the commonest mistake.
             */
            _uiState.update {
                it.copy(
                    busy = false,
                    errorKey = error,
                    username = if (error == null) "" else it.username,
                    password = "",
                    passwordVisible = false,
                    touched = error != null,
                )
            }
        }
    }
}
