package com.simplechat.app.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplechat.app.R
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.theme.LocalChatColors
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/*
 * 思考强度：输入栏右侧的一颗按钮 + 点开的一张滑块卡片。
 *
 * 它原先藏在模型浮层的「模型参数」折叠行里：换一次档位要点模型胶囊、
 * 点「模型参数」、再拖一条 20dp 高的细滑块，三步。而「这一句要不要多想一会儿」
 * 是每发一条消息都可能改的决定，该和发送键待在一起、一步到位。
 */

/** 思考强度浮层的宽度。 */
val ReasoningBubbleWidth = 268.dp

/**
 * 滑块上的档位，索引即位置：最左是「关闭」，往右五档越想越久。
 *
 * 「关」也是滑块上的一格，而不是另外一个开关：关和开、开多大，在手上是同一个
 * 动作 —— 把旋钮往左推到底就是关，往右推就是想得更久。它和「最弱一档」是
 * **相邻的两格**，不会混成一件事。
 */
internal val ReasoningStops: List<ReasoningLevel> = listOf(ReasoningLevel.OFF) + ReasoningLevel.sliderOrder

internal fun reasoningStopOf(level: ReasoningLevel): Int =
    ReasoningStops.indexOf(level).coerceAtLeast(0)

/** 每一档一句人话。API 的字面值（low / xhigh）说不清「我该选哪个」。 */
@Composable
private fun reasoningHint(level: ReasoningLevel): String = stringResource(
    when (level) {
        ReasoningLevel.OFF -> R.string.reasoning_desc_off
        ReasoningLevel.LOW -> R.string.reasoning_desc_low
        ReasoningLevel.MEDIUM -> R.string.reasoning_desc_medium
        ReasoningLevel.HIGH -> R.string.reasoning_desc_high
        ReasoningLevel.XHIGH -> R.string.reasoning_desc_xhigh
        ReasoningLevel.MAX -> R.string.reasoning_desc_max
    },
)

/**
 * 输入栏右侧的「思考强度」按钮。
 *
 * 和旁边的 ⊕ 同一个尺寸、同一种描边圆。当前档位画成**压在描边上的一段弧**：
 * 关 = 没有弧，max = 一整圈。不点开也看得出现在开没开、开得多大；
 * 写成文字（「xhigh」）的话这颗按钮会比发送键还宽。
 */
@Composable
fun ReasoningChip(
    level: ReasoningLevel,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    val accent = MaterialTheme.colorScheme.primary
    val on = level.enabled
    val stop = reasoningStopOf(level)
    val sweep by animateFloatAsState(
        targetValue = stop / ReasoningStops.lastIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 260f),
        label = "reasoningArc",
    )
    val description = stringResource(R.string.reasoning_chip_desc, level.label)
    val ring = colors.outline

    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (expanded) colors.capsuleSelected else Color.Transparent)
            .clickable(onClick = onClick)
            .drawBehind {
                val stroke = 1.dp.toPx()
                val arcStroke = 2.dp.toPx()
                // 底圈：与 ⊕ 同一根描边
                drawCircle(color = ring, radius = size.minDimension / 2f - stroke / 2f, style = Stroke(stroke))
                if (sweep > 0.001f) {
                    val inset = arcStroke / 2f
                    drawArc(
                        color = accent,
                        startAngle = -90f,
                        sweepAngle = 360f * sweep.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - inset * 2f, size.height - inset * 2f),
                        style = Stroke(width = arcStroke, cap = StrokeCap.Round),
                    )
                }
            }
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = AppIcons.Brain,
            contentDescription = null,
            tint = if (on) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            modifier = Modifier.size(17.dp),
        )
    }
}

/**
 * 思考强度卡片：标题 + 当前档位、一条滑块、一句说明。
 *
 * **拖的时候只预览，松手才提交。** 档位名和说明跟着手指实时变（否则不知道自己
 * 停在了哪一格），但 [onLevelChange] 只在松手时调一次。
 *
 * 为什么不边拖边提交：档位落盘走 DataStore，而 ViewModel 会把 DataStore 回流的值
 * 再写回界面状态。一次快速拖动跨过三格就是三次写入，三次回流陆续到达时，
 * 前两次带的已经是旧值 —— 旋钮和档位名会在松手之后来回跳两下。提交一次就没有这个问题。
 */
@Composable
fun ReasoningCard(
    level: ReasoningLevel,
    onLevelChange: (ReasoningLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    val stop = reasoningStopOf(level)
    // 拖动中手指所在的那一格；不在拖时为 null，显示的就是真实档位
    var preview by remember { mutableStateOf<Int?>(null) }
    val shownStop = preview ?: stop
    val shown = ReasoningStops[shownStop.coerceIn(0, ReasoningStops.lastIndex)]
    // 上一次显示的位置：档位名往哪个方向滑出去，取决于是调高了还是调低了
    var previousStop by remember { mutableIntStateOf(shownStop) }
    val rising = shownStop >= previousStop
    LaunchedEffect(shownStop) { previousStop = shownStop }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = AppIcons.Brain,
                contentDescription = null,
                tint = colors.processText,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = stringResource(R.string.reasoning_title),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                color = colors.processText,
                modifier = Modifier.weight(1f),
            )
            /*
             * 档位名：调高从下往上换，调低从上往下换。
             *
             * 只做淡入淡出的话，「high → xhigh」是两个长得很像的词原地换了一下，
             * 看不出动没动；带上方向，眼睛能直接读出「往上走了一格」。
             */
            AnimatedContent(
                targetState = shown,
                transitionSpec = {
                    val enter = slideInVertically(tween(160)) { height -> if (rising) height else -height } +
                        fadeIn(tween(120))
                    val exit = slideOutVertically(tween(160)) { height -> if (rising) -height else height } +
                        fadeOut(tween(90))
                    (enter togetherWith exit).using(SizeTransform(clip = false))
                },
                label = "reasoningLevelName",
            ) { target ->
                Text(
                    text = target.label,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = if (target.enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        EffortTrack(
            stop = stop,
            stops = ReasoningStops.size,
            valueText = shown.label,
            onPreview = { index -> preview = index },
            onCommit = { index ->
                preview = null
                val picked = ReasoningStops[index]
                if (picked != level) onLevelChange(picked)
            },
        )

        Spacer(Modifier.height(5.dp))

        // 两端各写一个词：左边是关，右边是最强。中间四格靠上面的档位名说明
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = ReasoningStops.first().label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = colors.placeholder,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = ReasoningStops.last().label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = colors.placeholder,
            )
        }

        Spacer(Modifier.height(8.dp))

        // 固定两行高：说明有长有短，不占位的话每换一档卡片都会跳一下
        Box(modifier = Modifier.heightIn(min = 34.dp)) {
            AnimatedContent(
                targetState = shown,
                transitionSpec = {
                    (fadeIn(tween(140)) togetherWith fadeOut(tween(90))).using(SizeTransform(clip = false))
                },
                label = "reasoningHint",
            ) { target ->
                Text(
                    text = reasoningHint(target),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                    color = colors.processText,
                )
            }
        }

        Text(
            text = stringResource(R.string.reasoning_note),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
            color = colors.placeholder,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 分档滑轨。
 *
 * 一条 34dp 高的胶囊轨道，旋钮嵌在轨道里面，旋钮左边的那一段填上强调色。
 * 手感上的四件事：
 *
 * 1. **旋钮跟手，不跳格**。位置是连续量；只有「离哪一格最近」变了才回报预览。
 *    每次都吸到格子上的话，手指在两格之间走，旋钮一格一格地蹦。
 * 2. **跨格有一下轻震**。六格之间没有刻度数字，换格的反馈靠触觉。
 * 3. **松手弹到最近的一格**，带一点过冲 —— 像被磁铁吸过去。
 * 4. **点轨道任意位置**，旋钮滑过去，而不是闪现。
 *
 * @param stop 当前档位的索引（真实值，不含拖动中的预览）。
 * @param stops 一共几格（≥ 2）。
 * @param valueText 当前显示的档位名，给读屏用。
 * @param onPreview 拖动中手指换到了另一格。
 * @param onCommit 松手：落在哪一格。每次手势恰好调用一次。
 */
@Composable
private fun EffortTrack(
    stop: Int,
    stops: Int,
    valueText: String,
    onPreview: (Int) -> Unit,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val last = (stops - 1).coerceAtLeast(1)
    // 旋钮位置，单位是「格」：0f..last
    val position = remember { Animatable(stop.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    // pointerInput 只在 key 变化时重建，回调要走 rememberUpdatedState 才拿得到最新的
    val preview by rememberUpdatedState(onPreview)
    val commit by rememberUpdatedState(onCommit)
    val currentStop by rememberUpdatedState(stop)

    val grow by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "effortThumbGrow",
    )

    // 档位从外面变了（换了模型、别处改了设置）：旋钮弹过去。拖动中不管，手指说了算
    LaunchedEffect(stop) {
        if (!dragging) position.animateTo(stop.toFloat(), SettleSpring)
    }

    val accent = MaterialTheme.colorScheme.primary
    val idleFill = colors.outline
    val trackColor = colors.fieldBackground
    val dotOnTrack = colors.placeholder.copy(alpha = 0.55f)
    val dotOnFill = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f)
    val thumbColor = Color.White
    val thumbShadow = Color.Black.copy(alpha = 0.16f)

    val trackHeight = with(density) { 34.dp.toPx() }
    val thumbRadius = with(density) { 13.dp.toPx() }
    val thumbGrowPx = with(density) { 1.5.dp.toPx() }
    val dotRadius = with(density) { 1.6.dp.toPx() }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .semantics {
                stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = stop.toFloat(),
                    range = 0f..last.toFloat(),
                    steps = (last - 1).coerceAtLeast(0),
                )
                setProgress { target ->
                    val index = target.roundToInt().coerceIn(0, last)
                    if (index != stop) onCommit(index)
                    true
                }
            }
            .pointerInput(last, trackHeight) {
                // 旋钮中心能到的最左 / 最右：比轨道两端各缩进半个轨道高
                val inset = trackHeight / 2f
                fun positionAt(x: Float): Float {
                    val usable = (size.width - inset * 2f).coerceAtLeast(1f)
                    return ((x - inset) / usable).coerceIn(0f, 1f) * last
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    var nearest = currentStop

                    fun moveTo(x: Float, glide: Boolean) {
                        val raw = positionAt(x)
                        scope.launch {
                            if (glide) {
                                // 第一下（点在轨道上）滑过去；之后跟手
                                position.animateTo(raw, spring(dampingRatio = 0.8f, stiffness = 900f))
                            } else {
                                position.snapTo(raw)
                            }
                        }
                        val index = raw.roundToInt().coerceIn(0, last)
                        if (index != nearest) {
                            nearest = index
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            preview(index)
                        }
                    }

                    moveTo(down.position.x, glide = true)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) break
                        if (pointer.position != pointer.previousPosition) {
                            moveTo(pointer.position.x, glide = false)
                        }
                        pointer.consume()
                    }

                    dragging = false
                    val landed = nearest
                    commit(landed)
                    /*
                     * 弹到落点。和上面的 snapTo 走同一个 scope，按先后顺序执行 ——
                     * 所以它一定排在最后一次「跟手」之后，不会被迟到的 snapTo 打断。
                     * 落定之后再对一次表：万一上层没接受这次改动，旋钮回到真实档位。
                     */
                    scope.launch {
                        position.animateTo(landed.toFloat(), SettleSpring)
                        if (currentStop != landed) position.animateTo(currentStop.toFloat(), SettleSpring)
                    }
                }
            },
    ) {
        val centerY = size.height / 2f
        val top = centerY - trackHeight / 2f
        val inset = trackHeight / 2f
        val usable = (size.width - inset * 2f).coerceAtLeast(1f)
        // 回弹过冲时 position 会略微越过两端，画的时候不钳：那一下「弹」正是要看见的
        val thumbX = inset + usable * (position.value / last)
        val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)

        // 轨道
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, top),
            size = Size(size.width, trackHeight),
            cornerRadius = radius,
        )

        // 填充：从最左到旋钮右缘。停在「关」那一格时是灰的，往右走一格内过渡到强调色
        val fillColor = lerp(idleFill, accent, position.value.coerceIn(0f, 1f))
        val fillEnd = (thumbX + inset).coerceIn(trackHeight, size.width)
        drawRoundRect(
            color = fillColor,
            topLeft = Offset(0f, top),
            size = Size(fillEnd, trackHeight),
            cornerRadius = radius,
        )

        // 每一格一个小点：告诉人「这条轨道是分档的，一共这么多格」
        for (index in 0..last) {
            val x = inset + usable * (index.toFloat() / last)
            drawCircle(
                color = if (x <= thumbX) dotOnFill else dotOnTrack,
                radius = dotRadius,
                center = Offset(x, centerY),
            )
        }

        // 旋钮：白色圆，按住时胀一点；下面压一圈很淡的影子把它从填充色上托起来
        val thumb = thumbRadius + thumbGrowPx * grow
        drawCircle(color = thumbShadow, radius = thumb + 1.dp.toPx(), center = Offset(thumbX, centerY + 1.dp.toPx()))
        drawCircle(color = thumbColor, radius = thumb, center = Offset(thumbX, centerY))
    }
}

/** 松手后吸到格子上：过冲一成多，像被磁铁吸过去再回正。 */
private val SettleSpring = spring<Float>(dampingRatio = 0.55f, stiffness = 380f)
