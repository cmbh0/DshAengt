package com.simplechat.app.ui.market

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.data.Res
import com.simplechat.app.market.Market
import com.simplechat.app.market.MarketPlugin
import com.simplechat.app.skill.SkillStore
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.CapsuleButton
import com.simplechat.app.ui.settings.SettingsBarAction
import com.simplechat.app.ui.settings.SettingsDialogActions
import com.simplechat.app.ui.settings.SettingsDialogFrame
import com.simplechat.app.ui.settings.SettingsDimens
import com.simplechat.app.ui.settings.SettingsEmptyState
import com.simplechat.app.ui.settings.SettingsHint
import com.simplechat.app.ui.settings.SettingsInputDialog
import com.simplechat.app.ui.settings.SettingsTopBar
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.launch

/**
 * 插件市场。
 *
 * 它不是应用商店，是**一个 JSON 索引 + 一堆 zip**：任何人都能维护一份索引
 * （GitHub Pages、自己的 nginx），用户也能把地址换成社区镜像。这是"生态"
 * 能起步的最小形态 —— 不用先有一台服务器、不用审核、不用账号。
 *
 * 页面本身只有三件事：这个源里有什么 / 我装没装 / 装。
 * 不做评分、评论、推荐位：那些在只有几十个插件的时候全是噪声。
 */
@Composable
fun MarketScreen(onBack: () -> Unit) {
    val colors = LocalChatColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val skills by SkillStore.skills.collectAsState()

    var plugins by remember { mutableStateOf<List<MarketPlugin>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var stale by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /** 正在安装的插件 id。同一时刻只装一个 —— 并发改同一个目录没有意义。 */
    var busy by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf(Market.DefaultSource) }
    var editingSource by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    val installedIds = remember(skills) { skills.map { it.id }.toSet() }

    suspend fun load() {
        loading = true
        error = null
        source = Market.source()
        Market.fetch(source).fold(
            onSuccess = {
                plugins = it.plugins
                stale = it.fromCache
            },
            onFailure = { failure ->
                plugins = emptyList()
                stale = false
                error = failure.message ?: Res.get(R.string.market_load_failed_short)
            },
        )
        loading = false
    }

    LaunchedEffect(Unit) {
        Market.init(context)
        SkillStore.refresh()
        load()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.settingsBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        SettingsTopBar(
            title = Res.get(R.string.market_title),
            onBack = onBack,
            trailing = when {
                loading -> Res.get(R.string.market_loading)
                stale -> Res.get(R.string.market_offline)
                else -> null
            },
            trailingAccent = stale,
            actions = {
                SettingsBarAction(
                    icon = AppIcons.Refresh,
                    contentDescription = Res.get(R.string.market_refresh),
                    enabled = !loading,
                    onClick = { scope.launch { load() } },
                )
            },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SettingsDimens.ScreenPadding, vertical = 8.dp),
        ) {
            note?.let {
                SettingsHint(text = it, modifier = Modifier.padding(vertical = 4.dp))
            }

            /*
             * 来源放在最上面一行。
             *
             * 市场最容易让人困惑的一点是"为什么里面只有这几个" —— 因为列表来自
             * 某一个 JSON 地址。把地址摆在页首、可点可换，这件事就不用解释。
             */
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(SettingsDimens.CardCorner))
                    .background(colors.card),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editingSource = true }
                        .padding(
                            horizontal = SettingsDimens.RowPadH,
                            vertical = SettingsDimens.RowPadV,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = AppIcons.Globe,
                        contentDescription = null,
                        tint = colors.placeholder,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = Res.get(R.string.market_source),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = hostOf(source),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = colors.placeholder,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = Res.get(R.string.market_change_source),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(SettingsDimens.CardCorner))
                    .background(colors.card),
            ) {
                when {
                    loading && plugins.isEmpty() -> SettingsEmptyState(
                        icon = AppIcons.Puzzle,
                        title = Res.get(R.string.market_loading),
                        body = Res.get(R.string.market_loading_body),
                    )

                    plugins.isEmpty() -> SettingsEmptyState(
                        icon = AppIcons.Puzzle,
                        title = Res.get(R.string.market_empty_title),
                        body = error ?: Res.get(R.string.market_empty_body),
                    )

                    else -> plugins.forEachIndexed { index, plugin ->
                        if (index > 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = SettingsDimens.RowPadH + 30.dp)
                                    .height(0.5.dp)
                                    .background(colors.divider),
                            )
                        }
                        PluginRow(
                            plugin = plugin,
                            installed = plugin.id in installedIds,
                            busy = busy == plugin.id,
                            onInstall = {
                                if (busy != null) return@PluginRow
                                busy = plugin.id
                                note = null
                                scope.launch {
                                    Market.install(plugin).fold(
                                        onSuccess = {
                                            note = Res.get(R.string.market_install_done, plugin.displayName)
                                        },
                                        onFailure = {
                                            note = Res.get(
                                                R.string.market_install_failed,
                                                it.message ?: Res.get(R.string.market_install_failed_short),
                                            )
                                        },
                                    )
                                    busy = null
                                }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            SettingsHint(text = Res.get(R.string.market_footer))
            Spacer(Modifier.size(24.dp))
        }
    }

    if (editingSource) {
        SettingsInputDialog(
            title = Res.get(R.string.market_source),
            initial = if (source == Market.DefaultSource) "" else source,
            placeholder = Market.DefaultSource,
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
            mono = true,
            paste = true,
            hint = Res.get(R.string.market_source_hint),
            onDismiss = { editingSource = false },
            onConfirm = { value ->
                editingSource = false
                Market.setSource(value)
                scope.launch { load() }
            },
        )
    }
}

/** 一行插件：图标 + 名字/版本 + 说明 + 右侧动作。 */
@Composable
private fun PluginRow(
    plugin: MarketPlugin,
    installed: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsDimens.RowPadH, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = AppIcons.Puzzle,
            contentDescription = null,
            tint = colors.processText,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = plugin.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (plugin.version.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = plugin.version,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = colors.placeholder,
                    )
                }
            }
            if (plugin.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = plugin.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.processText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val meta = listOfNotNull(
                plugin.author.takeIf { it.isNotBlank() }?.let { "by $it" },
                plugin.tags.take(3).joinToString(" · ").takeIf { it.isNotBlank() },
            ).joinToString("　")
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.placeholder,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        when {
            !plugin.installable -> Text(
                text = Res.get(R.string.market_unsupported),
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
                modifier = Modifier.padding(top = 2.dp),
            )

            installed -> Text(
                text = Res.get(R.string.market_installed),
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
                modifier = Modifier.padding(top = 6.dp),
            )

            else -> CapsuleButton(
                text = if (busy) Res.get(R.string.market_installing) else Res.get(R.string.market_install),
                onClick = onInstall,
                enabled = !busy,
                selected = true,
            )
        }
    }
}

/** 地址 → 域名。列表里不需要整条 URL，域名足够说明"这份列表从哪来"。 */
private fun hostOf(url: String): String = runCatching {
    java.net.URI(url).host ?: url
}.getOrDefault(url)
