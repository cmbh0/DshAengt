package com.simplechat.app.ui.runtime

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplechat.app.R
import com.simplechat.app.agent.MuslEnv
import com.simplechat.app.agent.MuslPython
import com.simplechat.app.agent.Shell
import com.simplechat.app.data.Res
import com.simplechat.app.ui.common.AppConfirmDialog
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.CapsuleButton
import com.simplechat.app.ui.common.SnakeProgressRing
import com.simplechat.app.ui.settings.SettingsDimens
import com.simplechat.app.ui.settings.SettingsTextAction
import com.simplechat.app.ui.settings.SettingsTopBar
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 运行环境。
 *
 * 两块，外加一块只在升级上来的设备上出现的：
 *
 * 1. **内置工具箱** —— 随 APK 分发的 BusyBox。它不需要安装，所以这张卡没有
 *    「安装」按钮，只回答两件事：现在能不能用、不能用是因为什么。「自检」会真的
 *    跑几条命令并把输出原样显示出来，用户截个图就是一份完整的反馈。
 * 2. **Python** —— 按需下载的 CPython。装、重装、卸载都在这里。
 * 3. **旧版 Ubuntu 环境** —— 2.4.x 及更早装过 proot + Ubuntu 的设备上，那几百 MB
 *    还躺在私有目录里。新版不再用它，但也不替用户做主删掉，给一个清理入口。
 *
 * 为什么不再有「装一个发行版」的选项：上一代靠 proot 翻译系统调用，在不少设备上
 * `cd` 失效、apt 读不了 dpkg 数据库，修一处漏一处。现在命令直接跑在手机内核上，
 * 这一整类问题不存在了；代价是没有 apt，要系统级软件包的任务交给云端工作环境。
 */
@Composable
fun RuntimeScreen(onBack: () -> Unit) {
    val colors = LocalChatColors.current
    val scope = rememberCoroutineScope()

    // ── 工具箱 ───────────────────────────────────────────────
    val unsupported = remember { MuslEnv.unsupportedReason() }
    var health by remember { mutableStateOf<MuslEnv.Health?>(null) }
    var checking by remember { mutableStateOf(false) }
    var checkOutput by remember { mutableStateOf<String?>(null) }

    // ── Python ───────────────────────────────────────────────
    var pythonVersion by remember { mutableStateOf<String?>(null) }
    var pythonBusy by remember { mutableStateOf(false) }
    var pythonProgress by remember { mutableStateOf(0f) }
    var pythonStage by remember { mutableStateOf("") }
    var pythonError by remember { mutableStateOf<String?>(null) }
    var confirmRemovePython by remember { mutableStateOf(false) }

    // ── 旧环境 ───────────────────────────────────────────────
    var legacyBytes by remember { mutableStateOf(0L) }
    var legacyPath by remember { mutableStateOf("") }
    var legacyBusy by remember { mutableStateOf(false) }
    var legacyError by remember { mutableStateOf<String?>(null) }
    var confirmRemoveLegacy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            health = MuslEnv.health()
            pythonVersion = MuslPython.installedVersion()
            val legacy = MuslEnv.legacyUbuntuDir()
            legacyPath = legacy?.absolutePath.orEmpty()
            // 先让卡片出来，再去数大小：几万个文件要数好几秒
            legacyBytes = if (legacy != null) 1L else 0L
            if (legacy != null) legacyBytes = MuslEnv.legacyUbuntuBytes().coerceAtLeast(1L)
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.settingsBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        SettingsTopBar(
            title = Res.get(R.string.runtime_title),
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── 工具箱 ───────────────────────────────────────
            val toolboxOk = health?.ok == true
            RuntimeCard(
                title = Res.get(R.string.runtime_toolbox),
                status = when {
                    checking -> Res.get(R.string.runtime_toolbox_checking)
                    health == null -> ""
                    toolboxOk -> MuslEnv.versionLabel()
                    else -> Res.get(R.string.runtime_toolbox_unavailable)
                },
                statusColor = when {
                    health != null && !toolboxOk -> colors.danger
                    toolboxOk -> colors.processText
                    else -> colors.placeholder
                },
                description = Res.get(R.string.runtime_toolbox_desc),
            ) {
                val problem = unsupported ?: health?.takeIf { !it.ok }?.detail
                if (problem != null) {
                    Spacer(Modifier.size(8.dp))
                    ErrorText(
                        if (unsupported != null) problem
                        else Res.get(R.string.runtime_toolbox_fallback) + "\n" + problem,
                    )
                }

                checkOutput?.let { output ->
                    Spacer(Modifier.size(8.dp))
                    SelectionContainer {
                        Text(
                            text = output,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.5.sp,
                                lineHeight = 15.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = colors.processText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.fieldBackground)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }

                Spacer(Modifier.size(12.dp))
                CapsuleButton(
                    text = Res.get(
                        if (checking) R.string.runtime_toolbox_checking else R.string.runtime_toolbox_check,
                    ),
                    enabled = !checking && unsupported == null,
                    onClick = {
                        checking = true
                        checkOutput = null
                        scope.launch {
                            val output = withContext(Dispatchers.IO) {
                                health = MuslEnv.health(refresh = true)
                                selfTest()
                            }
                            checkOutput = output
                            checking = false
                        }
                    },
                    leadingIcon = AppIcons.Terminal,
                )
            }

            // ── Python ───────────────────────────────────────
            val pythonInstalled = pythonVersion != null
            RuntimeCard(
                title = Res.get(R.string.runtime_python),
                status = when {
                    pythonBusy -> null
                    pythonInstalled -> "Python $pythonVersion"
                    else -> Res.get(R.string.runtime_python_absent)
                },
                statusColor = if (pythonInstalled) colors.processText else colors.placeholder,
                progress = pythonProgress.takeIf { pythonBusy },
                description = Res.get(R.string.runtime_python_desc, MuslPython.Version),
            ) {
                if (!toolboxOk && health != null && !pythonInstalled) {
                    Spacer(Modifier.size(8.dp))
                    ErrorText(Res.get(R.string.runtime_python_needs_toolbox))
                }
                if (pythonBusy) {
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = if (pythonStage.isBlank()) {
                            Res.get(R.string.runtime_preparing)
                        } else {
                            "$pythonStage  ${(pythonProgress * 100).toInt()}%"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = colors.processText,
                    )
                }
                pythonError?.let { message ->
                    Spacer(Modifier.size(8.dp))
                    ErrorText(message)
                }

                Spacer(Modifier.size(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CapsuleButton(
                        text = when {
                            pythonBusy -> Res.get(R.string.runtime_python_installing)
                            pythonInstalled -> Res.get(R.string.runtime_python_reinstall)
                            else -> Res.get(R.string.runtime_python_install)
                        },
                        enabled = !pythonBusy && toolboxOk,
                        onClick = {
                            pythonError = null
                            pythonBusy = true
                            pythonProgress = 0f
                            pythonStage = ""
                            scope.launch {
                                val result = MuslPython.install { fraction, stage ->
                                    pythonProgress = fraction
                                    pythonStage = stage
                                }
                                pythonBusy = false
                                result.onFailure {
                                    pythonError = it.message ?: Res.get(R.string.error_install_failed_short)
                                }
                                refresh()
                            }
                        },
                        leadingIcon = AppIcons.Download,
                    )
                    if (pythonInstalled && !pythonBusy) {
                        Spacer(Modifier.size(10.dp))
                        SettingsTextAction(
                            text = Res.get(R.string.runtime_python_remove),
                            onClick = { confirmRemovePython = true },
                            destructive = true,
                        )
                    }
                }
            }

            // ── 旧版 Ubuntu 环境 ─────────────────────────────
            if (legacyBytes > 0L) {
                RuntimeCard(
                    title = Res.get(R.string.runtime_legacy),
                    status = if (legacyBytes > 1L) formatSize(legacyBytes) else "",
                    statusColor = colors.placeholder,
                    description = Res.get(
                        R.string.runtime_legacy_desc,
                        if (legacyBytes > 1L) formatSize(legacyBytes) else "…",
                        legacyPath,
                    ),
                ) {
                    legacyError?.let { message ->
                        Spacer(Modifier.size(8.dp))
                        ErrorText(message)
                    }
                    Spacer(Modifier.size(12.dp))
                    CapsuleButton(
                        text = Res.get(
                            if (legacyBusy) R.string.runtime_legacy_removing else R.string.runtime_legacy_remove,
                        ),
                        enabled = !legacyBusy,
                        onClick = { confirmRemoveLegacy = true },
                        leadingIcon = AppIcons.Trash,
                    )
                }
            }

            Spacer(Modifier.size(4.dp))
        }
    }

    if (confirmRemovePython) {
        AppConfirmDialog(
            title = Res.get(R.string.runtime_python_remove),
            message = Res.get(R.string.runtime_python_remove_hint),
            confirmText = Res.get(R.string.runtime_python_remove),
            destructive = true,
            onDismiss = { confirmRemovePython = false },
            onConfirm = {
                confirmRemovePython = false
                scope.launch {
                    withContext(Dispatchers.IO) { MuslPython.uninstall() }
                    pythonError = null
                    refresh()
                }
            },
        )
    }

    if (confirmRemoveLegacy) {
        AppConfirmDialog(
            title = Res.get(R.string.runtime_legacy_remove),
            message = Res.get(R.string.runtime_legacy_remove_hint),
            confirmText = Res.get(R.string.runtime_legacy_remove),
            destructive = true,
            onDismiss = { confirmRemoveLegacy = false },
            onConfirm = {
                confirmRemoveLegacy = false
                legacyBusy = true
                legacyError = null
                scope.launch {
                    val removed = withContext(Dispatchers.IO) { MuslEnv.removeLegacyUbuntu() }
                    legacyBusy = false
                    if (!removed) legacyError = Res.get(R.string.runtime_legacy_remove_failed)
                    refresh()
                }
            },
        )
    }
}

/**
 * 一张环境卡片：标题 + 右侧状态（或进度环）+ 说明，下面是各自的按钮。
 *
 * @param status 右上角那行小字；传 null 且 [progress] 非空时改画进度环。
 */
@Composable
private fun RuntimeCard(
    title: String,
    status: String?,
    statusColor: Color,
    description: String,
    progress: Float? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalChatColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SettingsDimens.CardCorner))
            .background(colors.card)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (progress != null) {
                SnakeProgressRing(
                    progress = progress,
                    size = 22.dp,
                    strokeWidth = 2.5.dp,
                    showPercent = false,
                )
            } else if (!status.isNullOrBlank()) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = statusColor,
                )
            }
        }
        Spacer(Modifier.size(6.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
            color = colors.placeholder,
        )
        content()
    }
}

/** 错误原文。可选中：用户要能把它复制出来发给开发者。 */
@Composable
private fun ErrorText(message: String) {
    SelectionContainer {
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
            color = LocalChatColors.current.danger,
        )
    }
}

/**
 * 自检：跑几条有代表性的命令，把输出原样拿回来。
 *
 * 覆盖的是最容易在真机上出岔子的几处 —— applet 再执行（`ls`）、管道与外部
 * 命令（`gzip`）、DNS、以及 Python 的加载链。每条单独跑、单独报退出码：
 * 一条被系统杀掉不该让后面的结果都看不到。
 */
private suspend fun selfTest(): String {
    val health = MuslEnv.health()
    if (!health.ok) return health.detail
    val steps = buildList {
        add("uname -m && busybox | head -n 1")
        add("ls / | wc -l")
        add("echo dsha | gzip | gunzip")
        add("nslookup -type=a example.com 2>&1 | tail -n 3")
        if (MuslPython.isInstalled()) {
            add("python3 -c 'import sys, ssl, sqlite3; print(sys.version.split()[0], ssl.OPENSSL_VERSION)'")
        }
    }
    return buildString {
        for (step in steps) {
            val result = Shell.run(step, cwd = MuslEnv.homeDir(), timeoutSeconds = 30)
            append("$ ").append(step).append('\n')
            val output = (result.stdout.trimEnd() + "\n" + result.stderr.trimEnd()).trim()
            if (output.isNotEmpty()) append(output).append('\n')
            if (result.exitCode != 0 || result.timedOut) {
                append("[exit ").append(result.exitCode)
                if (result.timedOut) append(" timeout")
                Shell.signalHint(result.exitCode)?.let { append(" ").append(it) }
                append("]\n")
            }
        }
    }.trimEnd()
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%d MB".format(bytes / (1024 * 1024))
    else -> "%d KB".format((bytes / 1024).coerceAtLeast(1))
}
