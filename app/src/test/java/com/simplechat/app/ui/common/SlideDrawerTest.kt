package com.simplechat.app.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 侧边栏手势里**不依赖触摸事件**的那部分规则：松手去留、横向滚动子级的交接。
 *
 * 这些规则错了不会崩，只会「手感不对」—— 推回去一大半抽屉又弹出来、把代码块
 * 滚回行首时顺带拽出了会话列表。真机上要很久才会有人说得清是哪儿不对，
 * 所以把每一条都钉成一个用例。
 */
class SlideDrawerTest {

    private val drag = NestedScrollSource.UserInput
    private val fling = NestedScrollSource.SideEffect

    /** 抽屉宽 300px，方便心算：拖 30px = 进度 0.1。 */
    private fun handoff(state: SlideDrawerState, enabled: Boolean = true) =
        DrawerHandoff(state, CoroutineScope(Dispatchers.Unconfined)).apply {
            this.enabled = enabled
            drawerPx = 300f
            flingPx = 400f
        }

    // ── 松手去留 ──────────────────────────────────────────

    @Test
    fun `甩一下就按甩的方向走 不看拖了多远`() {
        assertTrue(settlesOpen(fraction = 0.05f, fling = 1, wasOpen = false))
        assertFalse(settlesOpen(fraction = 0.95f, fling = -1, wasOpen = true))
        // 方向与出发点相反也一样：拉出来一大半又往回甩 = 不开了
        assertFalse(settlesOpen(fraction = 0.8f, fling = -1, wasOpen = false))
        assertTrue(settlesOpen(fraction = 0.2f, fling = 1, wasOpen = true))
    }

    @Test
    fun `从收起出发 拉出两成多就算开`() {
        assertFalse(settlesOpen(fraction = 0.10f, fling = 0, wasOpen = false))
        assertFalse(settlesOpen(fraction = 0.21f, fling = 0, wasOpen = false))
        assertTrue(settlesOpen(fraction = 0.22f, fling = 0, wasOpen = false))
        assertTrue(settlesOpen(fraction = 0.60f, fling = 0, wasOpen = false))
    }

    @Test
    fun `从展开出发 推回两成多就算收`() {
        // 旧规则是「过 15% 就全开」：推回去一大半再松手，抽屉又弹了出来
        assertTrue(settlesOpen(fraction = 0.90f, fling = 0, wasOpen = true))
        assertFalse(settlesOpen(fraction = 0.78f, fling = 0, wasOpen = true))
        assertFalse(settlesOpen(fraction = 0.50f, fling = 0, wasOpen = true))
        assertFalse(settlesOpen(fraction = 0.16f, fling = 0, wasOpen = true))
    }

    @Test
    fun `速度不够不算甩`() {
        assertEquals(0, flingOf(velocityX = 399f, threshold = 400f))
        assertEquals(0, flingOf(velocityX = -399f, threshold = 400f))
        assertEquals(1, flingOf(velocityX = 401f, threshold = 400f))
        assertEquals(-1, flingOf(velocityX = -401f, threshold = 400f))
    }

    // ── 横向滚动子级的交接 ────────────────────────────────

    @Test
    fun `子级已经在最左边 向右拖出来的位移归抽屉`() {
        val state = SlideDrawerState()
        val handoff = handoff(state)

        val taken = handoff.onPostScroll(consumed = Offset.Zero, available = Offset(60f, 0f), source = drag)

        assertEquals(Offset(60f, 0f), taken)
        assertEquals(0.2f, state.fraction, 0.0001f)
    }

    @Test
    fun `这次手势里子级自己滚过 滚到头也不接手`() {
        val state = SlideDrawerState()
        val handoff = handoff(state)

        // 先是代码块自己往回滚了 30px
        handoff.onPostScroll(consumed = Offset(30f, 0f), available = Offset.Zero, source = drag)
        // 滚到行首了，手指还在往右：多出来的 20px 不该拽出抽屉
        val taken = handoff.onPostScroll(consumed = Offset(10f, 0f), available = Offset(20f, 0f), source = drag)
        val later = handoff.onPostScroll(consumed = Offset.Zero, available = Offset(50f, 0f), source = drag)

        assertEquals(Offset.Zero, taken)
        assertEquals(Offset.Zero, later)
        assertEquals(0f, state.fraction, 0f)
    }

    @Test
    fun `惯性滚到头不算在拉抽屉`() {
        val state = SlideDrawerState()
        val handoff = handoff(state)

        val taken = handoff.onPostScroll(consumed = Offset.Zero, available = Offset(120f, 0f), source = fling)

        assertEquals(Offset.Zero, taken)
        assertEquals(0f, state.fraction, 0f)
    }

    @Test
    fun `向左多出来的位移与抽屉无关`() {
        val state = SlideDrawerState()
        val handoff = handoff(state)

        val taken = handoff.onPostScroll(consumed = Offset.Zero, available = Offset(-80f, 0f), source = drag)

        assertEquals(Offset.Zero, taken)
        assertEquals(0f, state.fraction, 0f)
    }

    @Test
    fun `纵向滚动既不触发交接 也不算子级横向滚过`() {
        val state = SlideDrawerState()
        val handoff = handoff(state)

        val vertical = handoff.onPostScroll(consumed = Offset(0f, 50f), available = Offset(0f, 30f), source = drag)
        assertEquals(Offset.Zero, vertical)
        assertEquals(0f, state.fraction, 0f)

        // 之后的横向位移照常交接
        val taken = handoff.onPostScroll(consumed = Offset.Zero, available = Offset(30f, 0f), source = drag)
        assertEquals(Offset(30f, 0f), taken)
        assertEquals(0.1f, state.fraction, 0.0001f)
    }

    @Test
    fun `不在对话页时不接手`() {
        val state = SlideDrawerState()
        val handoff = handoff(state, enabled = false)

        val taken = handoff.onPostScroll(consumed = Offset.Zero, available = Offset(60f, 0f), source = drag)

        assertEquals(Offset.Zero, taken)
        assertEquals(0f, state.fraction, 0f)
    }

    @Test
    fun `拉出一截后往回拖 先把抽屉推回去 多的才还给子级`() {
        val state = SlideDrawerState()
        val handoff = handoff(state)
        handoff.onPostScroll(consumed = Offset.Zero, available = Offset(150f, 0f), source = drag)
        assertEquals(0.5f, state.fraction, 0.0001f)

        // 往回 60px：全部用来推抽屉
        val first = handoff.onPreScroll(available = Offset(-60f, 0f), source = drag)
        assertEquals(-60f, first.x, 0.01f)
        assertEquals(0.3f, state.fraction, 0.0001f)

        // 再往回 200px：抽屉只剩 90px 可推，其余 110px 留给子级去滚
        val second = handoff.onPreScroll(available = Offset(-200f, 0f), source = drag)
        assertEquals(-90f, second.x, 0.01f)
        assertEquals(0f, state.fraction, 0.0001f)
    }

    @Test
    fun `没在拉抽屉时 不抢子级向左的滚动`() {
        val state = SlideDrawerState()
        val handoff = handoff(state)

        val taken = handoff.onPreScroll(available = Offset(-60f, 0f), source = drag)

        assertEquals(Offset.Zero, taken)
        assertEquals(0f, state.fraction, 0f)
    }

    @Test
    fun `已经在拉抽屉时 子级随后的滚动不会把交接关掉`() {
        val state = SlideDrawerState()
        val handoff = handoff(state)
        handoff.onPostScroll(consumed = Offset.Zero, available = Offset(90f, 0f), source = drag)

        // 手指往回走过了头，子级滚了一点；再向右时仍然先回到子级、多出来的继续给抽屉
        handoff.onPreScroll(available = Offset(-120f, 0f), source = drag)
        handoff.onPostScroll(consumed = Offset(-30f, 0f), available = Offset.Zero, source = drag)
        val taken = handoff.onPostScroll(consumed = Offset(30f, 0f), available = Offset(45f, 0f), source = drag)

        assertEquals(Offset(45f, 0f), taken)
        assertEquals(0.15f, state.fraction, 0.0001f)
    }
}
