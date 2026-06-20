package `fun`.kirari.hanako.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
private val ContainerHeight = 58.dp
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
 * @param onDismiss 点击空白处关闭回调（动画开始前立即触发）
 * @param onDismissFinished 退场动画完全结束后回调（用于真正移除菜单）
 */
@Composable
fun BubbleMenu(
    anchorX: Int,
    anchorY: Int,
    settings: AppSettings,
    onItemClick: (BubbleMenuItem) -> Unit,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit = {},
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

    // 扇形开口始终朝向屏幕中心：气泡靠边时通过旋转整个菜单来保证完整显示。
    // 标准极坐标：0° 向右，90° 向上。屏幕坐标 y 向下，所以 dy 取反。
    val dx = 0.5f - relativeX
    val dy = relativeY - 0.5f
    val centerAngleDeg = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble()))
    val fanAngle = 90.0
    val startAngle = centerAngleDeg - fanAngle / 2
    val endAngle = centerAngleDeg + fanAngle / 2

    // 展开/退场动画控制
    var closing by remember { mutableStateOf(false) }
    val expansionProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        expansionProgress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
    }
    LaunchedEffect(closing) {
        if (closing) {
            expansionProgress.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
            onDismissFinished()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.12f * expansionProgress.value))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {
                    if (!closing) {
                        closing = true
                        onDismiss()
                    }
                }
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
            // 根据展开进度动态计算子项相对于中心的偏移位置
            val currentRadius = MenuRadius * expansionProgress.value
            val targetX = centerX + (currentRadius.value * cos(angleRad)).dp
            val targetY = centerY - (currentRadius.value * sin(angleRad)).dp

            val enabled = entry.isEnabled(settings)
            val checked = entry.isChecked(settings)

            MenuItem(
                entry = entry,
                centerX = targetX,
                centerY = targetY,
                index = index,
                totalCount = entries.size,
                enabled = enabled,
                checked = checked,
                closing = closing,
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
    totalCount: Int,
    enabled: Boolean,
    checked: Boolean,
    closing: Boolean,
    onClick: () -> Unit
) {
    val scale = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        scale.animateTo(1f, tween(180, delayMillis = index * 35))
        alpha.animateTo(1f, tween(180, delayMillis = index * 35))
    }
    LaunchedEffect(closing) {
        if (closing) {
            val reverseDelay = (totalCount - 1 - index) * 35
            scale.animateTo(0f, tween(160, delayMillis = reverseDelay))
            alpha.animateTo(0f, tween(160, delayMillis = reverseDelay))
        }
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
    }
}
