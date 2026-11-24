package com.humblesolutions.ainotetaker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.humblesolutions.ainotetaker.ui.theme.Tas
import com.humblesolutions.ainotetaker.ui.theme.TasShape

enum class ChipTone { NEUTRAL, PRIMARY, WARNING, DANGER, SUCCESS }

/** A small status or choice pill. Selected is the soft primary tint with primary text. */
@Composable
fun TasChip(
    text: String,
    modifier: Modifier = Modifier,
    tone: ChipTone = ChipTone.NEUTRAL,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = Tas.colors
    val background = when {
        selected -> colors.primaryWash
        tone == ChipTone.PRIMARY -> colors.primaryWash
        tone == ChipTone.WARNING -> colors.warningWash
        tone == ChipTone.DANGER -> colors.destructiveWash
        tone == ChipTone.SUCCESS -> colors.successWash
        else -> colors.card
    }
    val foreground = when {
        selected -> colors.primary
        tone == ChipTone.PRIMARY -> colors.primary
        tone == ChipTone.WARNING -> colors.warningWashForeground
        tone == ChipTone.DANGER -> colors.destructiveWashForeground
        tone == ChipTone.SUCCESS -> colors.successWashForeground
        else -> colors.mutedForeground
    }
    val border = when {
        selected -> colors.primary
        tone == ChipTone.NEUTRAL -> colors.border
        else -> null
    }
    val shape = RoundedCornerShape(TasShape.pill)

    Text(
        text = text,
        style = Tas.type.caption.copy(fontWeight = FontWeight.Bold),
        color = foreground,
        maxLines = 1,
        modifier = modifier
            .clip(shape)
            .background(background)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** The circle of initials that opens the profile. */
@Composable
fun Avatar(initials: String, modifier: Modifier = Modifier, size: Int = 38) {
    val colors = Tas.colors
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(TasShape.pill))
            .background(colors.primaryWash),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials,
            style = Tas.type.caption.copy(fontWeight = FontWeight.ExtraBold),
            color = colors.primary,
        )
    }
}
