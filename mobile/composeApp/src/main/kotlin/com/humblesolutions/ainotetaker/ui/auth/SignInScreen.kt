package com.humblesolutions.ainotetaker.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.humblesolutions.ainotetaker.model.SignOutReason
import com.humblesolutions.ainotetaker.ui.components.LogoMark
import com.humblesolutions.ainotetaker.ui.components.TasButton
import com.humblesolutions.ainotetaker.ui.components.TasCard
import com.humblesolutions.ainotetaker.ui.components.TasField
import com.humblesolutions.ainotetaker.ui.theme.Tas
import com.humblesolutions.ainotetaker.ui.theme.TasShape
import com.humblesolutions.ainotetaker.ui.theme.tr
import com.humblesolutions.ainotetaker.viewmodel.SignInViewModel

/**
 * The way in: the logo, then one card with two fields and a pill button. A notice only when there is something
 * true to say: a failed sign-in, or why the last session ended.
 */
@Composable
fun SignInScreen(
    viewModel: SignInViewModel,
    /** Why the last session ended, when it ended for a reason worth naming. */
    signedOutReason: SignOutReason? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = Tas.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val passwordFocus = remember { FocusRequester() }

    val noticeKey = state.errorKey
        ?: signedOutReason?.messageKey?.takeIf { !state.touched }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(56.dp))
        LogoMark(64.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            text = "AI Notebook",
            style = Tas.type.display2xl.copy(fontSize = 34.sp, lineHeight = 40.sp),
            color = colors.foreground,
        )
        Text(
            text = "Record meetings. Get clear notes.",
            style = Tas.type.body,
            color = colors.mutedForeground,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(32.dp))

        TasCard(padding = PaddingValues(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = tr("auth.signIn"),
                    style = Tas.type.heading,
                    color = colors.foreground,
                )

                TasField(
                    label = tr("auth.username"),
                    value = state.username,
                    onValueChange = viewModel::onUsername,
                    placeholder = tr("auth.usernamePlaceholder"),
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                    onImeAction = { passwordFocus.requestFocus() },
                    contentType = ContentType.EmailAddress + ContentType.Username,
                    leading = { MailGlyph(Modifier.size(19.dp), colors.faintForeground) },
                )

                TasField(
                    label = tr("auth.password"),
                    value = state.password,
                    onValueChange = viewModel::onPassword,
                    modifier = Modifier.focusRequester(passwordFocus),
                    placeholder = tr("auth.passwordPlaceholder"),
                    isPassword = true,
                    passwordVisible = state.passwordVisible,
                    imeAction = ImeAction.Done,
                    onImeAction = {
                        keyboard?.hide()
                        viewModel.onSubmit()
                    },
                    contentType = ContentType.Password,
                    leading = { LockGlyph(Modifier.size(19.dp), colors.faintForeground) },
                    trailing = {
                        val description = tr(
                            if (state.passwordVisible) "auth.hidePassword"
                            else "auth.showPassword",
                        )
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(TasShape.pill))
                                .clickable(onClick = viewModel::onTogglePasswordVisible)
                                .semantics { contentDescription = description },
                            contentAlignment = Alignment.Center,
                        ) {
                            EyeGlyph(
                                modifier = Modifier.size(21.dp),
                                tint = colors.faintForeground,
                                struck = state.passwordVisible,
                            )
                        }
                    },
                )

                AnimatedVisibility(
                    visible = noticeKey != null,
                    enter = fadeIn(tween(160)) + expandVertically(tween(180)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
                ) {
                    Notice(text = tr(noticeKey ?: "auth.cannotSignIn"))
                }

                TasButton(
                    text = if (state.busy) tr("auth.signingIn") else tr("auth.signIn"),
                    onClick = {
                        keyboard?.hide()
                        viewModel.onSubmit()
                    },
                    enabled = state.canSubmit,
                    busy = state.busy,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** One line, when there is something true to say. Failures only, so one tone. */
@Composable
private fun Notice(text: String) {
    val colors = Tas.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TasShape.radius))
            .background(colors.destructiveWash)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = text,
            style = Tas.type.bodySmall,
            color = colors.destructiveWashForeground,
        )
    }
}
