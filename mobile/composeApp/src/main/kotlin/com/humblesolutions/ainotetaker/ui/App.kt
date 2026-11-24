package com.humblesolutions.ainotetaker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.humblesolutions.ainotetaker.model.Session
import com.humblesolutions.ainotetaker.model.Startup
import com.humblesolutions.ainotetaker.model.User
import com.humblesolutions.ainotetaker.model.UserStatus
import com.humblesolutions.ainotetaker.navigation.Route
import com.humblesolutions.ainotetaker.ui.auth.SignInScreen
import com.humblesolutions.ainotetaker.ui.components.ButtonTone
import com.humblesolutions.ainotetaker.ui.components.TasButton
import com.humblesolutions.ainotetaker.ui.components.TopBar
import com.humblesolutions.ainotetaker.ui.components.LogoMark
import com.humblesolutions.ainotetaker.ui.profile.ProfileScreen
import com.humblesolutions.ainotetaker.ui.meetings.MeetingDetailScreen
import com.humblesolutions.ainotetaker.ui.meetings.MeetingsScreen
import com.humblesolutions.ainotetaker.ui.recorder.RecorderScreen
import com.humblesolutions.ainotetaker.ui.recorder.RecorderNotificationAction
import com.humblesolutions.ainotetaker.ui.recorder.RecorderWarningDialog
import com.humblesolutions.ainotetaker.ui.theme.Tas
import com.humblesolutions.ainotetaker.ui.theme.TasTheme
import com.humblesolutions.ainotetaker.ui.theme.tr
import com.humblesolutions.ainotetaker.util.PendingNotification
import com.humblesolutions.ainotetaker.viewmodel.SessionViewModel
import com.humblesolutions.ainotetaker.viewmodel.SignInViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The root, and the gate.
 *
 * FOUR STATES, NOT TWO. Loading is a real state — the app cannot tell a signed
 * out person from one whose token is still being read, and guessing either way
 * is wrong. Blocked is real too: somebody suspended, or still being
 * provisioned, is told what happened rather than bounced to the sign-in screen,
 * which reads like the app losing the tap.
 */
@Composable
fun App() {
    val sessionViewModel: SessionViewModel = viewModel()
    val session by sessionViewModel.session.collectAsStateWithLifecycle()
    val language by sessionViewModel.language.collectAsStateWithLifecycle()

    /*
     * Ticks rather than waits on a single delay, because both answers can
     * arrive at any point and the hand-off should land within a frame or two
     * of the later one — not at a fixed moment chosen in advance.
     */
    // Starts from the time already shown this app run, not from zero — see
    // `SessionViewModel.splashElapsedMs`.
    var elapsed by remember { mutableLongStateOf(sessionViewModel.splashElapsedMs) }
    val ready = Startup.isReady(
        sessionResolved = session !is Session.Loading,
        // The splash waits for the session alone.
        accessNeeded = false,
        accessResolved = true,
        elapsedMs = elapsed,
    )
    LaunchedEffect(ready) {
        while (!ready && isActive) {
            delay(Startup.TICK_MS)
            elapsed += Startup.TICK_MS
            sessionViewModel.splashElapsedMs = elapsed
        }
    }
    TasTheme(language = language) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Tas.colors.background),
        ) {
            if (!ready) {
                SplashScreen()
            } else when (val current = session) {
                // Unreachable once `ready` is true — kept because `Session` is
                // a sealed type and a silently missing branch is how the next
                // state added to it goes unhandled.
                is Session.Loading -> SplashScreen()

                is Session.SignedOut -> {
                    val signInViewModel: SignInViewModel = viewModel()
                    SignInScreen(
                        viewModel = signInViewModel,
                        /*
                         * COMPUTED, CARRIED, AND — UNTIL NOW — DROPPED HERE.
                         * `SignOutReason` exists so that somebody suspended
                         * mid-shift is told why, and it was threaded all the
                         * way through the sign-out and then never passed to a
                         * screen. The one case it was built for looked exactly
                         * like a session that had quietly expired.
                         */
                        signedOutReason = current.reason,
                    )
                }

                is Session.Blocked -> BlockedGate(
                    status = current.status,
                    reason = current.user.provisioningError,
                    onSignOut = sessionViewModel::onSignOut,
                )

                /*
                 * Signed in, and the account could not be read. NOT a sign-out
                 * — see Session.Unreachable. A transient failure heals itself
                 * and this screen disappears on its own; a rules refusal does
                 * not, which is what the retry is for.
                 */
                is Session.Unreachable -> UnreachableGate(
                    onRetry = sessionViewModel::onRetrySession,
                    onSignOut = sessionViewModel::onSignOut,
                )

                is Session.Active -> SignedInApp(
                    user = current.user,
                    onSignOut = sessionViewModel::onSignOut,
                )
            }
        }
    }
}

/**
 * Signed in, but not usable yet — or not any more.
 *
 * SAID PLAINLY. A suspended worker who lands back on the sign-in screen tries
 * their number again, and again, and then rings their leader. One sentence
 * costs nothing and saves that call.
 */
@Composable
private fun BlockedGate(status: UserStatus, reason: String?, onSignOut: () -> Unit) {
    val colors = Tas.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LogoMark(56.dp)
        Text(
            text = when (status) {
                UserStatus.SUSPENDED -> tr("auth.err.suspended")
                UserStatus.PROVISIONING_FAILED -> tr("common.state.errorTitle")
                else -> tr("common.state.loadingPage")
            },
            style = Tas.type.displayLg,
            color = colors.foreground,
            textAlign = TextAlign.Center,
        )
        if (status == UserStatus.PENDING_PROVISIONING) {
            // Milliseconds behind the write, not an error. Waiting is right;
            // accusing is not.
            CircularProgressIndicator(color = colors.primary)
        }
        reason?.let {
            Text(it, style = Tas.type.bodySmall, color = colors.mutedForeground, textAlign = TextAlign.Center)
        }
        TasButton(
            text = tr("common.nav.logOut"),
            onClick = onSignOut,
            tone = ButtonTone.OUTLINE,
            fillWidth = false,
        )
    }
}

/**
 * Signed in, and we could not find out whether the account is usable.
 *
 * NOT THE SIGN-IN SCREEN, which is where this used to land somebody. Reading
 * the account document can fail for reasons that have nothing to do with the
 * account — a cold start underground, a token whose claims are a second late —
 * and the old code read every one of them as "you were never registered" and
 * asked a worker at a doorstep for a password they did not have on them.
 *
 * TWO WAYS OUT, because the two failures differ. A dropped listener comes back
 * by itself and this screen vanishes; one the rules refused never retries, so
 * the retry rebuilds the whole chain — including the forced token refresh.
 */
@Composable
private fun UnreachableGate(onRetry: () -> Unit, onSignOut: () -> Unit) {
    val colors = Tas.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LogoMark(56.dp)
        Text(
            text = tr("auth.unreachable"),
            style = Tas.type.displayLg,
            color = colors.foreground,
            textAlign = TextAlign.Center,
        )
        TasButton(
            text = tr("common.action.retry"),
            onClick = onRetry,
            fillWidth = false,
        )
        TasButton(
            text = tr("common.nav.logOut"),
            onClick = onSignOut,
            tone = ButtonTone.OUTLINE,
            fillWidth = false,
        )
    }
}

/**
 * The app proper, once somebody is in: the recorder, and a small profile
 * reached from the bar above it.
 */
@Composable
private fun SignedInApp(
    user: User,
    onSignOut: () -> Unit,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()

    /** Tapping a recorder notification opens the recorder, wherever the app was. */
    val openRecorder by PendingNotification.openRecorder.collectAsStateWithLifecycle()
    LaunchedEffect(openRecorder) {
        if (!openRecorder) return@LaunchedEffect
        PendingNotification.consumeRecorder()
        navController.navigate(Route.Notetaker.path) {
            popUpTo(Route.Notetaker.path) { inclusive = true }
            launchSingleTop = true
        }
    }

    val currentRoute = backStack?.destination?.route
    val onRecorder = currentRoute == null || currentRoute == Route.Notetaker.path
    // Two tabs: the recorder and the person's meetings.
    val onTab = onRecorder || currentRoute == Route.Meetings.path
    fun openTab(route: Route) = navController.navigate(route.path) {
        popUpTo(Route.Notetaker.path) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    // The warning before a recording stops, on whichever screen is open.
    RecorderWarningDialog()
    // Pause, Stop or Stop now tapped on the lock screen, arriving after the unlock.
    RecorderNotificationAction()

    Scaffold(
        containerColor = Tas.colors.background,
        // Each screen draws its own band under the status bar.
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            // Hidden on the profile, which carries its own context.
            if (onTab) {
                TopBar(
                    user = user,
                    onProfile = { navController.navigate(Route.Profile.path) { launchSingleTop = true } },
                )
            }
        },
        bottomBar = {
            if (onTab) TabBar(onMeetings = !onRecorder, onRecorder = { openTab(Route.Notetaker) }, onMeetingsTab = { openTab(Route.Meetings) })
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Route.Notetaker.path,
            /*
             * EVERY SCREEN ENDS AT THE TOP OF THE KEYBOARD. The activity is in
             * resize mode (see the manifest), which reports the keyboard rather
             * than sliding the whole window up under it — the slide was what
             * dragged a screen's top bar out of place and left the end of a
             * form unreachable. Nothing resizes by itself in an edge-to-edge
             * app, so the room is made here, once, for every screen: the
             * bottom of the content area rises with the keyboard, a scrolling
             * screen keeps its focused field in view, and a button pinned to
             * the bottom rides on top of the keys. Consumed first, so the
             * Scaffold's own bottom padding is not counted twice.
             */
            modifier = Modifier.padding(padding).consumeWindowInsets(padding).imePadding(),
        ) {
            composable(Route.Notetaker.path) {
                RecorderScreen(me = user, viewModel = viewModel())
            }
            composable(Route.Meetings.path) {
                MeetingsScreen(viewModel = viewModel(), onOpen = { id -> navController.navigate(Route.Meeting.of(id)) })
            }
            composable(Route.Meeting.path) {
                MeetingDetailScreen(me = user, viewModel = viewModel(), onBack = { navController.popBackStack() })
            }
            composable(Route.Profile.path) {
                ProfileScreen(
                    user = user,
                    onBack = { navController.popBackStack() },
                    onSignOut = onSignOut,
                )
            }
        }
    }
}

/** The two tabs, Record and Meetings: white bar, hairline on top, the selected one in a soft pill. */
@Composable
private fun TabBar(onMeetings: Boolean, onRecorder: () -> Unit, onMeetingsTab: () -> Unit) {
    val colors = Tas.colors
    Column(Modifier.fillMaxWidth().background(colors.card).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((label, selected, onClick) in listOf(
                Triple("Record", !onMeetings, onRecorder),
                Triple(tr("meetings.tab"), onMeetings, onMeetingsTab),
            )) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (selected) colors.primaryWash else Color.Transparent)
                        .clickable(onClick = onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = Tas.type.bodySmall.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold),
                        color = if (selected) colors.primary else colors.mutedForeground,
                    )
                }
            }
        }
    }
}
