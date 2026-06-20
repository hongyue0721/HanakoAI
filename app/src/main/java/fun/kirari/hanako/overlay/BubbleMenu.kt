package `fun`.kirari.hanako.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `fun`.kirari.hanako.automation.BubbleMenuEntry
import `fun`.kirari.hanako.automation.BubbleMenuItem
import `fun`.kirari.hanako.automation.BubbleMenuRegistry
import `fun`.kirari.hanako.data.AppSettings
import kotlin.math.cos
import kotlin.math.sin

private val MenuRadius = 104.dp
private val ButtonSize = 46.dp
private val ContainerWidth = 58.dp
private val ContainerHeight = 68.dp
private val PrimaryPurple = Color(0xFF6750A4)
private val OnPrimary = Color.White
private val InactiveSurface = Color.White
private val OnInactive = Color(0xFF1D1B20)
private val DisabledSurface = Color(0xFFE0E0E0)
private val OnDisabled = Color(0xFF9E9E9E)

/**
 * 悬浮球扇形菜单（Material 风格，与气泡视觉统一）
 *
 * @param anchorX 气泡中心 X（px）
 * @param anchorY 气泡中心 Y（px）
 * @param settings 当前设置，用于计算开关状态
 * @param onItemClick 菜单项点击回调
 * @param onDismiss 点击空白处关闭回调
 */
@Composable
fun BubbleMenu(
    anchorX: Int,
    anchorY: Int,
    settings: AppSettings,
    onItemClick: (BubbleMenuItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = configuration.screenWidthDp.toFloat() * density.density
    val screenHeightPx = configuration.screenHeightDp.toFloat() * density.density
    val centerX = with(density) { anchorX.toDp() }
    val centerY = with(density) { anchorY.toDp() }
    val entries = BubbleMenuRegistry.entries

    val relativeX = anchorX.toFloat() / screenWidthPx
    val relativeY = anchorY.toFloat() / screenHeightPx

    // 垂直方向：上半屏向下展开，下半屏向上展开
    val expandDownward = relativeY < 0.5f
    val startAngle = if (expandDownward) 200.0 else 20.0
    val endAngle = if (expandDownward) 340.0 else 160.0

    // 水平自适应：靠边时整体平移
    val horizontalShift = when {
        relativeX > 0.75f -> -MenuRadius * 0.55f * ((relativeX - 0.75f) / 0.25f).coerceIn(0f, 1f)
        relativeX < 0.25f -> MenuRadius * 0.55f * ((0.25f - relativeX) / 0.25f).coerceIn(0f, 1f)
        else -> 0.dp
    }
    val adjustedCenterX = centerX + horizontalShift

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.12f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss
            )
    ) {
        val step = if (entries.size > 1) {
            (endAngle - startAngle) / (entries.size - 1)
        } else {
            0.0
        }
        entries.forEachIndexed { index, entry ->
            val angleDeg = startAngle + index * step
            val angleRad = Math.toRadians(angleDeg)
            val offsetX = adjustedCenterX + (MenuRadius.value * cos(angleRad)).dp
            val offsetY = centerY - (MenuRadius.value * sin(angleRad)).dp
            val enabled = entry.isEnabled(settings)
            val checked = entry.isChecked(settings)

            MenuItem(
                entry = entry,
                centerX = offsetX,
                centerY = offsetY,
                index = index,
                enabled = enabled,
                checked = checked,
                onClick = { onItemClick(entry.item) }
            )
        }
    }
}

@Composable
private fun MenuItem(
    entry: BubbleMenuEntry,
    centerX: Dp,
    centerY: Dp,
    index: Int,
    enabled: Boolean,
    checked: Boolean,
    onClick: () -> Unit
) {
    val scale = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        scale.animateTo(1f, tween(180, delayMillis = index * 35))
        alpha.animateTo(1f, tween(180, delayMillis = index * 35))
    }

    val backgroundColor = when {
        !enabled -> DisabledSurface
        checked -> PrimaryPurple
        else -> InactiveSurface
    }
    val contentColor = when {
        !enabled -> OnDisabled
        checked -> OnPrimary
        else -> OnInactive
    }
    val topLeftX = centerX - ContainerWidth / 2
    val topLeftY = centerY - ButtonSize / 2

    Box(
        modifier = Modifier
            .offset(topLeftX, topLeftY)
            .size(ContainerWidth, ContainerHeight)
            .scale(scale.value)
            .alpha(alpha.value),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .size(ButtonSize)
                .shadow(
                    elevation = if (checked) 4.dp else 3.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.15f),
                    spotColor = Color.Black.copy(alpha = 0.12f)
                )
                .background(backgroundColor, CircleShape)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(entry.iconRes),
                contentDescription = entry.label,
                tint = contentColor,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = entry.label,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.35f), CircleShape),
            color = Color.White,
            fontSize = 8.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            lineHeight = 10.sp
        )
    }
}
