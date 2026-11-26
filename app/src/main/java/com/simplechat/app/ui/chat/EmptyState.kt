package com.simplechat.app.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.data.HomeIcon
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppMenu
import com.simplechat.app.ui.common.AppMenuItem
import com.simplechat.app.ui.common.BounceScale
import com.simplechat.app.ui.common.bounceClick

/**
 * 空态：一枚图标 + 一句问候。
 *
 * 与 DeepSeek 首页同一套构图：正中一个方形 Logo，下面是粗体的一句话，
 * 不写功能说明、不放示例问题（那些都在输入框上方抢注意力）。
 *
 * 图标默认是应用自己的图标，**用户可以换成自己的图**：点一下图标弹出菜单
 * （更换 / 恢复默认），设置 → 应用 → 首页图标是同一件事的另一个入口。
 *
 * @param iconPath 自定义图标的路径。`null` = 设置还没读出来（先留空位，什么都不画）；
 *   `""` = 默认图标。
 * @param onPickIcon 从相册换一张。为 null 时图标不可点。
 * @param onResetIcon 恢复默认图标。
 */
@Composable
fun EmptyState(
    modifier: Modifier = Modifier,
    iconPath: String? = "",
    onPickIcon: (() -> Unit)? = null,
    onResetIcon: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        HomeIconBadge(iconPath = iconPath, onPick = onPickIcon, onReset = onResetIcon)

        Spacer(Modifier.size(20.dp))

        Text(
            text = stringResource(R.string.empty_greeting),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
    }
}

/** 图标要显示成什么。 */
private sealed interface HomeIconArt {
    /** 还不知道（设置没读完，或者自定义图正在解码）：留着空位，不画。 */
    data object Pending : HomeIconArt

    /** 应用自带的图标。 */
    data object Brand : HomeIconArt

    /** 用户自己的图。 */
    data class Custom(val bitmap: ImageBitmap) : HomeIconArt
}

private val HomeIconSize = 64.dp
private val HomeIconShape = RoundedCornerShape(18.dp)

@Composable
private fun HomeIconBadge(
    iconPath: String?,
    onPick: (() -> Unit)?,
    onReset: (() -> Unit)?,
) {
    /*
     * 「先留空，再淡入」，而不是「先画默认图标，读到自定义图再换」。
     *
     * 设置和图片都是异步读的。冷启动的头几帧里既不知道用户换没换过图标，
     * 也还没解出那张图 —— 这时先画默认图标的话，换过图标的用户每次打开 App
     * 都会看见默认图标闪一下。宁可空几十毫秒。
     *
     * 回到这一页时不会再空：`remember(iconPath)` 之外还有 [HomeIconCache]。
     */
    var art by remember(iconPath) {
        mutableStateOf(
            when {
                iconPath == null -> HomeIconArt.Pending
                iconPath.isBlank() -> HomeIconArt.Brand
                else -> HomeIconCache.get(iconPath)?.let { HomeIconArt.Custom(it) } ?: HomeIconArt.Pending
            },
        )
    }
    LaunchedEffect(iconPath) {
        if (iconPath.isNullOrBlank() || art != HomeIconArt.Pending) return@LaunchedEffect
        val bitmap = HomeIcon.load(iconPath)?.asImageBitmap()
        // 文件没了 / 坏了（比如换机后只恢复了设置）：退回默认图标，而不是一直空着
        art = if (bitmap != null) {
            HomeIconCache.put(iconPath, bitmap)
            HomeIconArt.Custom(bitmap)
        } else {
            HomeIconArt.Brand
        }
    }

    // 第一次有东西可画时淡入；之后换图（Brand ↔ Custom）直接切，不再闪一下
    val appear = remember { Animatable(if (art == HomeIconArt.Pending) 0f else 1f) }
    LaunchedEffect(art != HomeIconArt.Pending) {
        if (art != HomeIconArt.Pending) appear.animateTo(1f, tween(durationMillis = 140))
    }

    var menuOpen by remember { mutableStateOf(false) }
    val custom = art is HomeIconArt.Custom
    val changeLabel = stringResource(R.string.home_icon_change)

    Box {
        Box(
            modifier = Modifier
                .size(HomeIconSize)
                .graphicsLayer { alpha = appear.value }
                .then(
                    if (onPick != null) {
                        Modifier.bounceClick(
                            shape = HomeIconShape,
                            pressedScale = BounceScale.Tile,
                            onClickLabel = changeLabel,
                            role = Role.Button,
                            onClick = { menuOpen = true },
                        )
                    } else {
                        Modifier.clip(HomeIconShape)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (val current = art) {
                HomeIconArt.Pending -> Unit
                HomeIconArt.Brand -> Image(
                    painter = painterResource(R.drawable.ic_brand),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                is HomeIconArt.Custom -> Image(
                    bitmap = current.bitmap,
                    contentDescription = null,
                    // 存下来的已经是正方形（见 HomeIcon.compose），这里只是保险
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (onPick != null) {
            AppMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                alignEnd = false,
            ) {
                AppMenuItem(
                    label = changeLabel,
                    icon = AppIcons.Image,
                    onClick = {
                        menuOpen = false
                        onPick()
                    },
                )
                if (custom && onReset != null) {
                    AppMenuItem(
                        label = stringResource(R.string.home_icon_reset),
                        icon = AppIcons.RotateCcw,
                        onClick = {
                            menuOpen = false
                            onReset()
                        },
                    )
                }
            }
        }
    }
}

/**
 * 最近一张自定义图标的内存缓存。
 *
 * 只留一张：图标同一时刻只有一个。有了它，从会话切回空白页时图标是**同步**拿到的，
 * 不会每次都空一下再淡入。
 */
private object HomeIconCache {
    private var path: String? = null
    private var bitmap: ImageBitmap? = null

    fun get(key: String): ImageBitmap? = if (key == path) bitmap else null

    fun put(key: String, value: ImageBitmap) {
        path = key
        bitmap = value
    }
}
