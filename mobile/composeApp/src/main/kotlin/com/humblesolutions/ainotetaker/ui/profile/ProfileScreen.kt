package com.humblesolutions.ainotetaker.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.humblesolutions.ainotetaker.model.Role
import com.humblesolutions.ainotetaker.model.User
import com.humblesolutions.ainotetaker.ui.components.Avatar
import com.humblesolutions.ainotetaker.ui.components.BackHeader
import com.humblesolutions.ainotetaker.ui.components.ButtonTone
import com.humblesolutions.ainotetaker.ui.components.ChipTone
import com.humblesolutions.ainotetaker.ui.components.LogoLockup
import com.humblesolutions.ainotetaker.ui.components.TasButton
import com.humblesolutions.ainotetaker.ui.components.TasCard
import com.humblesolutions.ainotetaker.ui.components.TasChip
import com.humblesolutions.ainotetaker.ui.theme.Tas
import com.humblesolutions.ainotetaker.ui.theme.tr

/** Who you are, and the way out. */
@Composable
fun ProfileScreen(
    user: User,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
) {
    val colors = Tas.colors
    val isAdmin = user.role == Role.ADMIN || user.role == Role.SUPER_ADMIN

    Column(Modifier.fillMaxSize().background(colors.background)) {
        BackHeader(tr("profile.title"), onBack)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                TasCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Avatar(user.initials, size = 52)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(user.name, style = Tas.type.subheading, color = colors.foreground)
                            (user.email ?: user.phone.takeIf { it.isNotBlank() })?.let {
                                Text(it, style = Tas.type.caption, color = colors.mutedForeground)
                            }
                        }
                        TasChip(text = if (isAdmin) "Admin" else "Member", tone = if (isAdmin) ChipTone.PRIMARY else ChipTone.NEUTRAL)
                    }
                }
            }

            item {
                TasButton(
                    text = tr("common.nav.logOut"),
                    onClick = onSignOut,
                    tone = ButtonTone.OUTLINE,
                )
            }

            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LogoLockup(
                        markSize = 22.dp,
                        textStyle = Tas.type.bodySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold),
                    )
                }
            }
        }
    }
}
