package com.auracode.assistant.toolwindow.drawer.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import kotlinx.coroutines.delay

@Composable
internal fun AboutSettingsPage(
    p: DesignPalette,
) {
    val t = assistantUiTokens()
    val version = remember { AboutPluginInfo.pluginVersion() }
    var qqGroupCopied by remember { mutableStateOf(false) }

    LaunchedEffect(qqGroupCopied) {
        if (!qqGroupCopied) return@LaunchedEffect
        delay(1_600)
        qqGroupCopied = false
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(t.spacing.lg),
    ) {
        AboutHeroCard(
            p = p,
            version = version,
        )
        SettingsField(
            p = p,
            title = AuraCodeBundle.message("settings.about.repository.label"),
            description = AuraCodeBundle.message("settings.about.repository.hint"),
        ) {
            AboutLinkRow(
                p = p,
                iconPath = "/icons/document.svg",
                value = AboutPluginInfo.repositoryUrl,
                actionText = AuraCodeBundle.message("settings.about.repository.action"),
                onValueClick = { openExternalUrl(AboutPluginInfo.repositoryUrl) },
                onActionClick = { openExternalUrl(AboutPluginInfo.repositoryUrl) },
            )
        }
        SettingsField(
            p = p,
            title = AuraCodeBundle.message("settings.about.version.label"),
            description = AuraCodeBundle.message("settings.about.version.hint"),
        ) {
            AboutBadge(
                p = p,
                text = "v$version",
            )
        }
        SettingsField(
            p = p,
            title = AuraCodeBundle.message("settings.about.community.label"),
            description = AuraCodeBundle.message("settings.about.community.hint"),
        ) {
            AboutLinkRow(
                p = p,
                iconPath = "/icons/community.svg",
                value = AboutPluginInfo.qqGroupNumber,
                actionText = if (qqGroupCopied) {
                    AuraCodeBundle.message("settings.about.community.action.copied")
                } else {
                    AuraCodeBundle.message("settings.about.community.action")
                },
                onValueClick = {
                    qqGroupCopied = copyToClipboard(AboutPluginInfo.qqGroupNumber)
                },
                onActionClick = {
                    qqGroupCopied = copyToClipboard(AboutPluginInfo.qqGroupNumber)
                },
            )
        }
        Text(
            text = AuraCodeBundle.message("settings.about.footer"),
            color = p.textMuted,
            style = MaterialTheme.typography.caption,
        )
    }
}

@Composable
private fun AboutHeroCard(
    p: DesignPalette,
    version: String,
) {
    val t = assistantUiTokens()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.topBarBg.copy(alpha = 0.84f), androidx.compose.foundation.shape.RoundedCornerShape(t.spacing.lg))
            .border(
                width = 1.dp,
                color = p.accent.copy(alpha = 0.32f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(t.spacing.lg),
            )
            .padding(horizontal = t.spacing.lg, vertical = t.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(t.spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(t.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(t.spacing.md))
                        .background(p.accent.copy(alpha = 0.16f))
                        .border(
                            width = 1.dp,
                            color = p.accent.copy(alpha = 0.34f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(t.spacing.md),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource("/icons/toolwindow-stripe.svg"),
                        contentDescription = "Aura Code",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(t.spacing.xs)) {
                    Text(
                        text = AuraCodeBundle.message("settings.about.hero.title"),
                        color = p.textPrimary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.h6,
                    )
                    Text(
                        text = AuraCodeBundle.message("settings.about.hero.subtitle"),
                        color = p.textSecondary,
                        style = MaterialTheme.typography.body2,
                    )
                }
            }
            AboutBadge(
                p = p,
                text = "v$version",
            )
        }
        Text(
            text = AuraCodeBundle.message("settings.about.hero.body"),
            color = p.textSecondary,
            style = MaterialTheme.typography.body2,
        )
    }
}

@Composable
private fun AboutLinkRow(
    p: DesignPalette,
    iconPath: String,
    value: String,
    actionText: String,
    onValueClick: () -> Unit,
    onActionClick: () -> Unit,
) {
    val t = assistantUiTokens()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(t.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconPath),
                contentDescription = value,
                tint = p.textSecondary,
                modifier = Modifier.size(t.controls.iconLg),
            )
            Text(
                text = value,
                color = p.linkColor,
                style = MaterialTheme.typography.body2,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable(onClick = onValueClick),
            )
        }
        Spacer(Modifier.width(t.spacing.md))
        SettingsActionButton(
            p = p,
            text = actionText,
            emphasized = false,
            onClick = onActionClick,
        )
    }
}

@Composable
private fun AboutBadge(
    p: DesignPalette,
    text: String,
) {
    val t = assistantUiTokens()
    Box(
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(t.spacing.sm))
            .background(p.accent.copy(alpha = 0.14f))
            .border(
                width = 1.dp,
                color = p.accent.copy(alpha = 0.32f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(t.spacing.sm),
            )
            .padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = p.accent,
            style = MaterialTheme.typography.body2,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun openExternalUrl(url: String): Boolean {
    return runCatching {
        if (!Desktop.isDesktopSupported()) return false
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.BROWSE)) return false
        desktop.browse(URI(url))
        true
    }.getOrDefault(false)
}

private fun copyToClipboard(value: String): Boolean {
    return runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null)
        true
    }.getOrDefault(false)
}
