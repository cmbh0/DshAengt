package com.simplechat.app.ui.common

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 侧边栏控制器。
 *
 * 只保留一个 0..1 的进度：`0` = 收起，`1` = 完全展开。宽度、蒙版、
 * 投影全部由它推导 —— 三个值各存一份的话，拖到一半必然对不齐。
 */
class SlideDrawerState {

    var fraction by mutableFloatStateOf(0f)
        private set

    /** 抽屉是不是"处于打开意图"（动画目标 / 拖过一半）。 */
    val isOpen: Boolean get() = fraction > 0.001f

    private var job: Job? = null

    fun open(scope: CoroutineScope) = animateTo(scope, 1f)

    fun close(scope: CoroutineScope) = animateTo(scope, 0f)

    private fun animateTo(scope: CoroutineScope, target: Float) {
        job?.cancel()
        job = scope.launch {
            animate(
                initialValue = fraction,
                targetValue = target,
                animationSpec = tween(MotionDurationMs, easing = MotionEasing),
            ) { value, _ -> fraction = value }
        }
        // 动画结束把精度收干净，避免停在 0.9999 让"露出一个像素"
        if (target == 0f) job?.invokeOnCompletion { fraction = 0f }
    }

    /** 拖动。delta 是"进度增量"，由调用方按宽度换算好。 */
    fun dragBy(delta: Float) {
        job?.cancel()
        fraction = (fraction + delta).coerceIn(0f, 1f)
    }

    /**
     * 松手：决定是全开还是收回去。
     *
     * 先看**甩没甩**：手指离开时还带着明显的速度，就按速度的方向走 —— 轻轻一甩
     * 就该开 / 该收，不必真的拖过半个抽屉。没甩再看**拖到了哪儿**，而且门槛
     * 按「这次拖动是从哪边出发的」来定：从收起出发，拉出 [OpenThreshold] 就算开；
     * 从展开出发，推回去同样的一截就算收。
     *
     * 早先只有一条「过 15% 就全开」。往外拉时它没问题，往回推时却意味着
     * 要一直推到只剩 15% 才收得掉 —— 推回去一大半再松手，抽屉又弹了出来。
     *
     * @param fling 松手时的甩动方向：`> 0` 向右（开），`< 0` 向左（收），`0` 没甩。
     * @param wasOpen 这次拖动开始时抽屉是不是开着的。
     */
    fun settle(scope: CoroutineScope, fling: Int = 0, wasOpen: Boolean = false) {
        if (settlesOpen(fraction, fling, wasOpen)) open(scope) else close(scope)
    }

    fun toggle(scope: CoroutineScope) {
        if (isOpen) close(scope) else open(scope)
    }
}

@Composable
fun rememberSlideDrawerState(): SlideDrawerState = remember { SlideDrawerState() }

/** 不靠甩、只靠拖：拖出（或推回）这么大一截就算数。300dp 的抽屉上约 66dp。 */
private const val OpenThreshold = 0.22f

/** 松手时抽屉该全开（true）还是收起（false）。规则见 [SlideDrawerState.settle]。 */
internal fun settlesOpen(fraction: Float, fling: Int, wasOpen: Boolean): Boolean = when {
    fling > 0 -> true
    fling < 0 -> false
    wasOpen -> fraction > 1f - OpenThreshold
    else -> fraction >= OpenThreshold
}

/** 松手速度超过它才算「甩」。与 `DrawerLayout` 用的是同一个量级。 */
private val FlingVelocity = 400.dp

/** 抽屉收起时，从屏幕左缘这么宽的一条里起手，总能拉出抽屉。 */
private val EdgeWidth = 28.dp

/**
 * 页面中间起手时，横向位移至少是纵向的这么多倍才算「在拉抽屉」。
 *
 * 1.6 约等于与水平线夹角 32° 以内。竖着滑消息时手指难免带一点横向分量，
 * 没有这条的话，起手那一下偏了几个像素就会把抽屉拽出来。
 */
private const val HorizontalDominance = 1.6f

/*
 * 没有蒙版，也没有投影。
 *
 * 这是照官方 chat-demo 抄的：它的移动端抽屉 `drawerMask` **不带任何背景色**，
 * 只负责点击关闭；抽屉本体也没有 `box-shadow`。层次只靠两样东西 ——
 * 比页面暗/亮一档的底色，和一条 1px 右边框。
 *
 * 加蒙版（哪怕 0.32）会立刻变成"弹窗"，把"同一页里拉出来的一列"讲成
 * "另一件事压上来了"；投影同理。官方克制到这个程度，是有道理的。
 */

/** 抽屉宽度上限。官方桌面 261px、移动 300px，这里取 300dp。 */
private val DrawerMaxWidth = 300.dp

/** 抽屉右侧至少要让出的宽度：官方 `calc(100vw - 48px)` 的 48。 */
private val DrawerMinGap = 48.dp

/**
 * 侧边栏：**盖在主页面上的抽屉**，页面本身不动。
 *
 * ### 三版之间的取舍
 *
 * - 缩一圈加圆角（最早）：主页面被塞进一个框，观感像"应用被降级"。
 * - 整页右推（1.6.0）：页面被推出屏幕，右边只剩一条缝。
 * - 拼接一列（1.7.x）：页面被压窄，输入栏和标题一起挤成一团 —— 手机上
 *   两边都不够宽，这条路走不通。
 *
 * 这一版回到最朴素的抽屉，**尺寸与表面规格全部照官方 chat-demo**：
 * 主页面保持原样，抽屉从左边滑出来盖住它，宽度 300dp 封顶、右侧至少
 * 让出 48dp；底色比页面暗/亮一档，右边缘一条 1px 边框。
 *
 * **没有蒙版、没有投影**。官方的 `drawerMask` 是透明的、`.drawer` 没有
 * `box-shadow` —— 抽屉和页面是同一层的东西，压暗或投影都会把它讲成
 * "弹窗压上来了"。层次只靠那一点点底色差和那条线。
 *
 * 主页面不缩放、不位移、不加圆角、不变窄 —— 被盖住的部分不用管，
 * 用户要看它就把抽屉收起来。
 */
@Composable
fun SlideDrawer(
    state: SlideDrawerState,
    modifier: Modifier = Modifier,
    /** 展开后的宽度占屏宽的比例。 */
    widthFraction: Float = 0.78f,
    /**
     * 抽屉收起时，在页面**任意位置**向右拖都能把它拉出来。
     *
     * `false` 时只认左缘那一条。只在对话页打开：设置、终端这些页面里横向拖动
     * 另有含义，或者干脆不该弹出会话列表。
     */
    openFromAnywhere: Boolean = false,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        /*
         * 宽度照官方那条 CSS：`width: 300px; max-width: calc(100vw - 48px)`。
         *
         * 比例参数只是上限，真正的尺寸由 300dp 封顶、并且必须给右侧留出
         * 48dp —— 手机再窄也不会压到"抽屉盖满、看不出底下还有页面"。
         * 搜索态传 1f，走全宽分支。
         */
        val drawerWidth = if (widthFraction >= 0.999f) {
            maxWidth
        } else {
            minOf(maxWidth * widthFraction, DrawerMaxWidth, maxWidth - DrawerMinGap)
                .coerceAtLeast(200.dp)
        }
        val drawerPx = with(density) { drawerWidth.toPx() }
        val flingPx = with(density) { FlingVelocity.toPx() }
        val t = state.fraction

        /*
         * 手势协程里读的是这几个「最新值」，而不是把它们当 pointerInput 的 key。
         *
         * 进出搜索态时抽屉宽度是逐帧动画的；拿宽度当 key 的话，那段时间里手势处理
         * 每一帧都被重建一次，正在进行的拖动会被掐断。
         */
        val widthPx by rememberUpdatedState(drawerPx)
        val anywhere by rememberUpdatedState(openFromAnywhere)

        // 横向滚动的子级滚到头之后把剩下的位移交过来（见 DrawerHandoff）
        val handoff = remember(state, scope) { DrawerHandoff(state, scope) }
        SideEffect {
            handoff.enabled = openFromAnywhere
            handoff.drawerPx = drawerPx
            handoff.flingPx = flingPx
        }

        /*
         * 主页面：原宽、原位、原样。
         *
         * 手势层与内容层分开 —— 这一层不动，只负责判"这一下是不是在拉抽屉"。
         *
         * ### 怎么做到「哪儿都能拉」又不抢别人的横向滑动
         *
         * 靠的是事件的先后：这一层包在整页外面，同一个事件**子级先收到**。
         * 代码块、表格、附件条这些自己会横向滚动的东西越过 slop 就把事件 consume 了，
         * `awaitHorizontalTouchSlopOrCancellation` 看到事件已被消费就返回 null ——
         * 我们直接退出，一个事件都不碰。消息列表的纵向滚动同理：竖着滑先越过它的
         * slop，这里就不会启动。
         *
         * 所以这里不能改成「先下手」的写法（放到 Initial pass，或者用一越过 slop
         * 就无条件 consume 的 `detectHorizontalDragGestures`）：那样页面里所有的
         * 横向滑动都会被抽屉吃掉。
         *
         * 剩下两种会误触的情况，各有一条规则：
         * - 收起时**向左**拖：与抽屉无关，不接（不 consume，继续等）。
         * - 页面中间起手、方向不够横：不接（见 [HorizontalDominance]）。
         */
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(handoff)
                .pointerInput(state, flingPx) {
                    val edgePx = EdgeWidth.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 新的一次触摸：上一次交接若没收尾（理论上不会），在这里兜住
                        handoff.onGestureStart()
                        val wasOpen = state.isOpen
                        val fromEdge = down.position.x <= edgePx
                        if (!wasOpen && !fromEdge && !anywhere) return@awaitEachGesture

                        val velocity = VelocityTracker()
                        velocity.addPointerInputChange(down)
                        var overSlop = 0f
                        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                            val accept = when {
                                // 抽屉露着：往哪边拖都是在动它
                                wasOpen -> true
                                // 收起时向左拖：不是在拉抽屉
                                over <= 0f -> false
                                // 左缘起手：和以前一样，横向越过 slop 就接
                                fromEdge -> true
                                // 页面中间起手：要明显是横着拖的
                                else -> {
                                    val moved = change.position - down.position
                                    abs(moved.x) > abs(moved.y) * HorizontalDominance
                                }
                            }
                            if (accept) {
                                change.consume()
                                overSlop = over
                            }
                        } ?: return@awaitEachGesture

                        state.dragBy(overSlop / widthPx)
                        followDrag(drag, velocity) { delta -> state.dragBy(delta / widthPx) }
                        state.settle(scope, flingOf(velocity.calculateVelocity().x, flingPx), wasOpen)
                    }
                },
        ) {
            content()
        }

        /*
         * 空白区域的点击层：**透明**，不加任何底色。
         *
         * 与官方 `drawerMask` 一样，点一下把抽屉收起来。不加灰，是因为抽屉和页面
         * 本来是同一层的东西，压暗就把关系讲错了。
         *
         * 除了点，也认**拖**：抽屉开着时在右边露出的那一条页面上往左一拨，
         * 和在抽屉上往左推是同一个意思。
         */
        if (t > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f)
                    .pointerInput(state, flingPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val velocity = VelocityTracker()
                            velocity.addPointerInputChange(down)
                            var overSlop = 0f
                            val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                                change.consume()
                                overSlop = over
                            }
                            if (drag == null) {
                                // 没拖起来：手指原地抬起就是「点了一下空白」
                                val up = currentEvent.changes.firstOrNull { it.id == down.id }
                                if (up != null && !up.pressed && !up.isConsumed) {
                                    up.consume()
                                    state.close(scope)
                                }
                                return@awaitEachGesture
                            }
                            state.dragBy(overSlop / widthPx)
                            followDrag(drag, velocity) { delta -> state.dragBy(delta / widthPx) }
                            state.settle(scope, flingOf(velocity.calculateVelocity().x, flingPx), wasOpen = true)
                        }
                    },
            )
        }

        /*
         * 抽屉本体。
         *
         * `translationX` 从 -drawerPx（完全在屏外）走到 0 —— 只做平移，
         * 不做缩放：缩放会让里面的字号在动画期间"呼吸"，很廉价。
         */
        if (t > 0f) {
            Box(
                modifier = Modifier
                    .zIndex(2f)
                    .width(drawerWidth)
                    .fillMaxHeight()
                    .offset { IntOffset((-drawerPx * (1f - t)).roundToInt(), 0) }
                    .pointerInput(state, flingPx) {
                        /*
                         * 在抽屉上横向拖：跟手，松手时按速度和位置决定去留。
                         *
                         * 和页面那一层同一套写法（子级先收到事件，列表的纵向滚动、
                         * 行上的长按都不受影响），只是这里不分方向 —— 抽屉已经露着，
                         * 往哪边拖都是在动它。
                         */
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val velocity = VelocityTracker()
                            velocity.addPointerInputChange(down)
                            var overSlop = 0f
                            val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                                change.consume()
                                overSlop = over
                            } ?: return@awaitEachGesture
                            state.dragBy(overSlop / widthPx)
                            followDrag(drag, velocity) { delta -> state.dragBy(delta / widthPx) }
                            state.settle(scope, flingOf(velocity.calculateVelocity().x, flingPx), wasOpen = true)
                        }
                    },
            ) {
                drawerContent()
            }
        }
    }
}

/**
 * 越过 slop 之后：抽屉跟着手指走，直到抬手。
 *
 * 抬手那一下也要喂给速度计 —— 它靠「最后一次移动到抬手隔了多久」判断手指是不是
 * 停住了才抬起来的。不喂的话，拖出一截、停半秒再松手，算出来的仍是停住之前的
 * 速度，抽屉会被当成「甩了一下」朝那个方向飞出去。
 */
private suspend fun AwaitPointerEventScope.followDrag(
    first: PointerInputChange,
    velocity: VelocityTracker,
    onDelta: (Float) -> Unit,
) {
    velocity.addPointerInputChange(first)
    horizontalDrag(first.id) { change ->
        velocity.addPointerInputChange(change)
        onDelta(change.positionChange().x)
        change.consume()
    }
    currentEvent.changes.forEach { change ->
        if (change.changedToUpIgnoreConsumed()) velocity.addPointerInputChange(change)
    }
}

/** 松手速度 → 甩动方向：`1` 向右、`-1` 向左、`0` 不算甩。 */
internal fun flingOf(velocityX: Float, threshold: Float): Int = when {
    velocityX > threshold -> 1
    velocityX < -threshold -> -1
    else -> 0
}

/**
 * 把横向滚动子级**滚到头之后剩下的位移**接过来拉抽屉。
 *
 * 代码块、表格这类东西自己会横向滚动，它们一越过 slop 就把触摸事件拿走了 ——
 * 页面那层手势根本看不到这次拖动。对话里代码一多，半个屏幕都是这种区域，
 * 「在页面任意位置向右拖」就成了一句空话。
 *
 * 嵌套滚动正好补上这一块：子级滚不动的那部分位移会沿着这条链往上交。规则：
 *
 * - 只接**向右**的、**手指拖出来**的位移（惯性甩过来的不算 —— 把代码块甩回行首
 *   不该顺带甩出一个抽屉）。
 * - 这次手势里子级自己**滚动过**，就整次都不接：用户是在把代码滚回行首，
 *   滚到头之后多出来的那一点不是在拉抽屉。只有「起手时就已经在最左边」才交接。
 * - 抽屉被拉出一截后手指往回走：先把抽屉推回去，推完才轮到子级滚动。
 */
internal class DrawerHandoff(
    private val state: SlideDrawerState,
    private val scope: CoroutineScope,
) : NestedScrollConnection {
    var enabled = false
    var drawerPx = 1f
    var flingPx = 0f

    /** 这次手势里，子级自己横向滚过没有。 */
    private var childScrolled = false

    /** 这次手势里抽屉是不是正被这条链拉着。 */
    private var pulling = false

    fun onGestureStart() {
        if (pulling) state.settle(scope)
        pulling = false
        childScrolled = false
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!pulling || source != NestedScrollSource.UserInput || available.x >= 0f) return Offset.Zero
        val before = state.fraction
        state.dragBy(available.x / drawerPx)
        return Offset((state.fraction - before) * drawerPx, 0f)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (!enabled || source != NestedScrollSource.UserInput) return Offset.Zero
        if (!pulling && consumed.x != 0f) childScrolled = true
        if (childScrolled || available.x <= 0f) return Offset.Zero
        pulling = true
        state.dragBy(available.x / drawerPx)
        return Offset(available.x, 0f)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val wasPulling = pulling
        pulling = false
        childScrolled = false
        if (!wasPulling) return Velocity.Zero
        state.settle(scope, flingOf(available.x, flingPx))
        // 速度归抽屉了：子级不该在抽屉滑出来的同时自己再惯性滚一段
        return available
    }
}

/**
 * 侧边栏的"纸面"。
 *
 * 三件事，全部照官方 chat-demo 的 `.sider`：
 *
 * 1. 底色用 `sidebar`（比页面暗/亮一档），不是卡片白；
 * 2. 右边缘一条 1px 边框 —— 这是抽屉与页面之间唯一的"线"；
 * 3. 没有圆角、没有投影。
 *
 * 圆角会在屏幕边缘露出底下的内容（上一版"白边"的来源），投影会把
 * "同一页里拉出来的一列"讲成"浮起来的弹窗"。两者都不加。
 */
@Composable
fun DrawerSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalChatColors.current
    /*
     * 只铺底色，不描边。
     *
     * 之前加过一条 1.2dp 的右缘线，想着"两页之间要有一条分界" ——
     * 实际看起来像给抽屉套了个框，比不加更难收拾。侧边栏和页面本来就
     * 差一档底色（`sidebar` vs `pageBackground`），边界由那点色差说，
     * 加线是重复表达，而且永远比色差显眼。
     *
     * 官方 chat-demo 那条 `border-l1` 之所以成立，是因为它的侧边栏和页面
     * **同色**、只能靠线分开；我们的底色已经分开了，就不需要。
     */
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.sidebar),
    ) {
        content()
    }
}
