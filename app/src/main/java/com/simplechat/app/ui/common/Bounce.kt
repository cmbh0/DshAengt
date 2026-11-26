package com.simplechat.app.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.findNearestAncestor
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * 按压回弹。
 *
 * 点一张卡片：按下去它缩一点，松手弹回来并**略微过冲**再落定。位移很小
 * （1.5%～6%），目的不是「好玩」，而是让手指知道「按到了」—— 水波纹只说了
 * 「这里被碰了」，回弹说的是「这是一个能按下去的东西」。
 *
 * 三种用法，按「谁在缩」来选：
 *
 * ```
 * 独立的一张卡片     Modifier.bounceClick(shape, background = …) { … }
 * 分组卡片里的一行   卡片：Modifier.bounceHost()
 *                    行：  Modifier.relayClick { … }
 * 已经有自己的点击   Modifier.pressBounce(interactionSource)
 * ```
 *
 * 分组卡片（设置页那种一张卡里好几行的）缩的是**整张卡**而不是被点的那一行：
 * 卡片才是眼睛里的那个「物体」，单独缩一行会在卡片中间撕出两道缝。
 */

/** 按下去缩到多少。面积越大，同样的比例对应的位移越多，所以越大的东西越克制。 */
object BounceScale {
    /** 一张分组卡片（里面有好几行）。 */
    const val Group = 0.985f

    /** 独立的一张卡片、列表里的一行。 */
    const val Card = 0.97f

    /** 小块：附件缩略图、标签、圆按钮。 */
    const val Tile = 0.93f
}

/**
 * 松手后的回弹。
 *
 * 阻尼比 0.42：过冲约两成（缩了 3% 的卡片会弹到 100.6% 再回来），肉眼能看出
 * 「弹了一下」，又不至于像果冻。刚度 420 让整个过程在 400ms 左右落定 ——
 * 比页面转场（300ms）略长一点，转场盖过去时它正好收尾。
 */
private val BounceBack = spring<Float>(dampingRatio = 0.42f, stiffness = 420f)

/** 按下：快进慢出，90ms。要比手指抬起来得快，否则轻点时根本看不到它缩。 */
private val BounceDown = tween<Float>(durationMillis = 90, easing = LinearOutSlowInEasing)

/** 手势被滚动接管：安静地回到原位，不弹。那不是一次点击，不该有点击的反馈。 */
private val BounceCancel = spring<Float>(dampingRatio = 1f, stiffness = 700f)

/**
 * 跟着 [source] 的按压状态缩放。**放在 `clip` / `background` 之前**，
 * 底色、内容、水波纹才会作为一个整体一起缩。
 */
fun Modifier.pressBounce(
    source: InteractionSource,
    pressedScale: Float = BounceScale.Card,
): Modifier = composed {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(source, pressedScale) {
        source.interactions
            // 只认按压。焦点 / 悬停事件也走这条流，不滤掉的话它们会把
            // 进行到一半的回弹动画取消，卡片就停在一个缩了一半的尺寸上
            .filterIsInstance<PressInteraction>()
            .collectLatest { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> scale.animateTo(pressedScale, BounceDown)
                    is PressInteraction.Release -> {
                        /*
                         * 轻点的时候，「按下」和「松开」几乎同时到：按下动画刚起步
                         * 就被这里取消了。直接弹回去等于什么都没发生，所以先把
                         * 剩下的那一截走完（60ms），再弹 —— 轻点也看得到回弹。
                         */
                        val shallow = scale.value > pressedScale + (1f - pressedScale) * 0.3f
                        if (shallow) {
                            scale.animateTo(pressedScale, tween(60, easing = LinearOutSlowInEasing))
                        }
                        scale.animateTo(1f, BounceBack)
                    }
                    is PressInteraction.Cancel -> scale.animateTo(1f, BounceCancel)
                }
            }
    }
    graphicsLayer {
        val value = scale.value
        scaleX = value
        scaleY = value
    }
}

/**
 * 带回弹的点击 —— 独立卡片用它**替换** `.clip(shape) … .clickable { }`。
 *
 * 要放在画底色的修饰符之前：
 * ```
 * Modifier
 *     .fillMaxWidth()
 *     .bounceClick(RoundedCornerShape(16.dp), background = colors.card) { open() }
 *     .padding(16.dp)
 * ```
 * 它自己负责裁剪（水波纹跟着 [shape] 走），所以原来的 `clip` 可以去掉。
 * 底色也可以直接交给它（[background]），这样原来的
 * `.clip(s).background(c).clickable { }` 三行正好换成一行。
 *
 * @param background 卡片底色；不传就不画，由后面的修饰符自己画。
 * @param onLongClick 非空时同时接长按（列表里「长按出菜单」的那种行）。
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.bounceClick(
    shape: Shape = RectangleShape,
    background: Color = Color.Unspecified,
    enabled: Boolean = true,
    pressedScale: Float = BounceScale.Card,
    onClickLabel: String? = null,
    role: Role? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    val base = pressBounce(source, pressedScale)
        .clip(shape)
        .then(if (background.isSpecified) Modifier.background(background) else Modifier)
    if (onLongClick != null) {
        base.combinedClickable(
            interactionSource = source,
            indication = indication,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onLongClick = onLongClick,
            onClick = onClick,
        )
    } else {
        base.clickable(
            interactionSource = source,
            indication = indication,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
    }
}

// ── 分组卡片：行按下去，整张卡回弹 ─────────────────────────

/**
 * 让一个「卡片容器」成为回弹宿主：里面任何一行用 [relayClick] 被按下时，
 * 缩的是这整张卡。**放在 `clip` / `background` 之前。**
 *
 * ```
 * Column(
 *     Modifier
 *         .fillMaxWidth()
 *         .bounceHost()
 *         .clip(RoundedCornerShape(16.dp))
 *         .background(colors.card),
 * ) { 行… }
 * ```
 *
 * 行是怎么找到卡片的：靠修饰符节点树（[TraversableNode]），不靠 CompositionLocal ——
 * 这样任何一个自己画的卡片容器都只要多写这一行，不必把内容再包一层。
 */
fun Modifier.bounceHost(pressedScale: Float = BounceScale.Group): Modifier = composed {
    val host = remember { MutableInteractionSource() }
    this.then(BounceHostElement(host)).pressBounce(host, pressedScale)
}

/**
 * 分组卡片里的一行用的点击：和 `clickable` 一样，另外把「按下 / 松开」转告给
 * 所在的 [bounceHost]。不在这种卡片里时就是一个普通的 `clickable`。
 *
 * 行各自保留自己的 InteractionSource（水波纹只亮被按的那一行）。不能让所有行
 * 共用卡片那一个：那样按一行，整张卡每一行都会一起亮。
 */
fun Modifier.relayClick(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    clickable(
        interactionSource = source,
        indication = LocalIndication.current,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onClick = onClick,
    ).then(BounceRelayElement(source))
}

private object BounceHostKey

private class BounceHostNode(var source: MutableInteractionSource) : Modifier.Node(), TraversableNode {
    override val traverseKey: Any = BounceHostKey
}

private data class BounceHostElement(
    private val source: MutableInteractionSource,
) : ModifierNodeElement<BounceHostNode>() {
    override fun create() = BounceHostNode(source)

    override fun update(node: BounceHostNode) {
        node.source = source
    }
}

private class BounceRelayNode(var source: InteractionSource) : Modifier.Node() {
    private var job: Job? = null

    override fun onAttach() = restart()

    override fun onDetach() {
        job?.cancel()
        job = null
    }

    fun restart() {
        job?.cancel()
        job = coroutineScope.launch {
            source.interactions
                .filterIsInstance<PressInteraction>()
                .collect { interaction ->
                    // 每次都现找：行可能被挪到另一张卡片里（LazyColumn 复用、条件分支）
                    val host = findNearestAncestor(BounceHostKey) as? BounceHostNode
                    host?.source?.emit(interaction)
                }
        }
    }
}

private data class BounceRelayElement(
    private val source: InteractionSource,
) : ModifierNodeElement<BounceRelayNode>() {
    override fun create() = BounceRelayNode(source)

    override fun update(node: BounceRelayNode) {
        if (node.source !== source) {
            node.source = source
            node.restart()
        }
    }
}
