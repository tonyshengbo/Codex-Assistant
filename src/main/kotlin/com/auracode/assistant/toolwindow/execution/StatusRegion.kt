package com.auracode.assistant.toolwindow.execution

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.ToolWindowUiText
import com.auracode.assistant.toolwindow.shared.assistantUiTokens
import com.auracode.assistant.toolwindow.shared.resolve
import kotlinx.coroutines.delay

private const val TOAST_DURATION_MS: Long = 2400L
private const val TURN_TICK_MS: Long = 250L

@Composable
internal fun TurnStatusRegion(
    p: DesignPalette,
    state: StatusAreaState,
) {
    val turnStatus = state.turnStatus ?: return
    val t = assistantUiTokens()
    val appearance = turnStatusAppearanceSpec()
    val infiniteTransition = rememberInfiniteTransition(label = "turn-status-loading")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "turn-status-loading-rotation",
    )
    var nowMs by remember(turnStatus.startedAtMs) { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(turnStatus.startedAtMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(TURN_TICK_MS)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.topStripBg.copy(alpha = appearance.containerAlpha))
            .defaultMinSize(minHeight = appearance.minHeight)
            .padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.spacing.sm),
    ) {
        Icon(
            painter = painterResource("/icons/loading.svg"),
            contentDescription = null,
            tint = p.accent,
            modifier = Modifier
                .size(appearance.indicatorSize)
                .rotate(rotation),
        )
        Text(
            text = turnStatus.label.resolve(),
            color = p.textPrimary,
            style = MaterialTheme.typography.subtitle1.copy(
                fontSize = appearance.labelFontSize,
                fontWeight = FontWeight.Medium,
            ),
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .background(
                    color = p.accent.copy(alpha = appearance.elapsedChipAlpha),
                    shape = RoundedCornerShape(999.dp),
                )
                .padding(horizontal = t.spacing.sm, vertical = t.spacing.xs),
        ) {
            Text(
                text = ToolWindowUiText.formatDuration(nowMs - turnStatus.startedAtMs),
                color = p.textSecondary,
                style = MaterialTheme.typography.overline.copy(
                    fontSize = appearance.elapsedFontSize,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
    }
}

@Composable
internal fun StatusToastOverlay(
    p: DesignPalette,
    state: StatusAreaState,
    modifier: Modifier = Modifier,
) {
    val toast = state.toast
    var nowMs by remember(toast?.id) { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(toast?.id) {
        if (toast == null) return@LaunchedEffect
        nowMs = System.currentTimeMillis()
        delay(TOAST_DURATION_MS)
        nowMs = System.currentTimeMillis()
    }

    val isVisible = toast != null && nowMs - toast.createdAtMs < TOAST_DURATION_MS
    AnimatedVisibility(visible = isVisible, modifier = modifier) {
        val t = assistantUiTokens()
        Box(
            modifier = Modifier
                .background(p.topBarBg.copy(alpha = 0.98f), RoundedCornerShape(t.spacing.sm))
                .padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
        ) {
            Text(
                text = toast?.text?.resolve().orEmpty(),
                color = p.textPrimary,
                style = MaterialTheme.typography.body2,
            )
        }
    }
}
