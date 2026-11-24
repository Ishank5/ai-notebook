package com.humblesolutions.ainotetaker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.humblesolutions.ainotetaker.ui.theme.Tas
import com.humblesolutions.ainotetaker.ui.theme.TasShape

enum class ButtonTone { PRIMARY, OUTLINE, DESTRUCTIVE, GHOST }

/**
 * The app's button: a pill, 52dp tall, full width by default. A disabled one keeps its colour, faded.
 */
@Composable
fun TasButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.PRIMARY,
    enabled: Boolean = true,
    busy: Boolean = false,
    fillWidth: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = Tas.colors
    val clickable = enabled && !busy
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val background = when (tone) {
        ButtonTone.PRIMARY -> if (pressed) colors.primaryPressed else colors.primary
        ButtonTone.DESTRUCTIVE -> colors.destructive
        ButtonTone.OUTLINE -> if (pressed) colors.muted else colors.card
        ButtonTone.GHOST -> if (pressed) colors.muted else Color.Transparent
    }
    val foreground = when (tone) {
        ButtonTone.PRIMARY -> colors.primaryForeground
        ButtonTone.DESTRUCTIVE -> colors.destructiveForeground
        ButtonTone.OUTLINE -> colors.foreground
        ButtonTone.GHOST -> colors.accentForeground
    }
    val alpha = if (clickable) 1f else 0.5f
    val shape = RoundedCornerShape(TasShape.pill)

    Box(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .defaultMinSize(minHeight = TasShape.button)
            .clip(shape)
            .background(background.copy(alpha = background.alpha * alpha))
            .then(if (tone == ButtonTone.OUTLINE) Modifier.border(1.dp, colors.border, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = clickable, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = foreground,
                )
            } else {
                leading?.invoke()
            }
            Text(
                text = text,
                style = Tas.type.body.copy(fontWeight = FontWeight.Bold),
                color = foreground.copy(alpha = alpha),
                textAlign = TextAlign.Center,
            )
        }
    }
}
