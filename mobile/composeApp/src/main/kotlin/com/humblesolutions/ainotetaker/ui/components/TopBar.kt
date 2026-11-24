package com.humblesolutions.ainotetaker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.humblesolutions.ainotetaker.model.User
import com.humblesolutions.ainotetaker.ui.theme.Tas
import com.humblesolutions.ainotetaker.ui.theme.TasShape
import com.humblesolutions.ainotetaker.ui.theme.tr

/** The logo on the left; your initials on the right open the profile. */
@Composable
fun TopBar(
    user: User,
    onProfile: () -> Unit,
) {
    val profileLabel = tr("profile.title")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tas.colors.background)
            .statusBarsPadding()
            .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LogoLockup(markSize = 30.dp)
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(TasShape.tap)
                .clip(RoundedCornerShape(TasShape.pill))
                .clickable(onClick = onProfile)
                .semantics { contentDescription = profileLabel },
            contentAlignment = Alignment.Center,
        ) {
            Avatar(user.initials, size = 36)
        }
    }
}

/** The band at the top of a screen reached from another: back, and a title. */
@Composable
fun BackHeader(title: String, onBack: () -> Unit) {
    val colors = Tas.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val back = tr("common.action.back")
        Box(
            modifier = Modifier
                .size(TasShape.tap)
                .clip(RoundedCornerShape(TasShape.pill))
                .clickable(onClick = onBack)
                .semantics { contentDescription = back },
            contentAlignment = Alignment.Center,
        ) {
            BackGlyph(Modifier.size(22.dp), colors.foreground)
        }
        Text(title, style = Tas.type.heading, color = colors.foreground, maxLines = 1, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun BackGlyph(modifier: Modifier, tint: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.62f, h * 0.2f)
            lineTo(w * 0.32f, h * 0.5f)
            lineTo(w * 0.62f, h * 0.8f)
        }
        drawPath(
            path,
            color = tint,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = w * 0.1f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            ),
        )
    }
}
