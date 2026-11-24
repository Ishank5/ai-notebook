package com.humblesolutions.ainotetaker.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.humblesolutions.ainotetaker.ui.components.LogoMark
import com.humblesolutions.ainotetaker.ui.theme.Tas

/**
 * The first frame: the logo mark and the name on the app background, held until the session is known. No
 * spinner; the mark fades up.
 */
@Composable
fun SplashScreen() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "splashFade",
    )

    Box(
        modifier = Modifier.fillMaxSize().background(Tas.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.systemBarsPadding().padding(32.dp).alpha(alpha),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LogoMark(76.dp)
            Text(
                text = "AI Notebook",
                style = Tas.type.displayXl,
                color = Tas.colors.foreground,
            )
        }
    }
}
