package com.simplechat.app.terminal

import com.simplechat.app.agent.Shell
import com.simplechat.app.agent.Workspace
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 终端里的一行。 */
data class TerminalLine(
    val text: String,
    val kind: Kind,
) {
    enum class Kind { INPUT, OUTPUT, ERROR, INFO }
}

/**
 * 会话式终端。
 *
 * 移动端做**真 PTY** 不值当：Android 没有公开的 forkpty，得带一份 C 的
 * `libterm`，体积和崩溃面都不小，而实际用法几乎都是"敲一条，看结果"。
 *
 * 所以这里是**命令会话**：每条命令起一个 `sh -c` 子进程，但**工作目录由
 * 会话维护** —— `cd` 被截下来改状态，不真的执行。这样 `cd /sdcard` 之后
 * 下一条命令照样在那个目录里，用起来和真终端没差别，只是不支持
 * `vim` 这类需要交互的程序（移动端也基本不会用）。
 */
class TerminalSession(
    /**
     * 只读会话：AI 执行过的命令记录。
     *
     * 它不是"又一条 shell"，而是"AI 干过什么"的流水账 —— 所以终端页
     * 对这类会话隐藏快捷键与输入行，也不允许 `submit`。
     */
    val readOnly: Boolean = false,
) {

    private companion object {
        /**
         * 行数上限。
         *
         * AI 一次任务能跑几十条命令，每条带输出就是几十行；不封顶的话
         * 长时间挂着会一直涨。4000 行约等于几十屏，往回翻也翻不到那么远。
         */
        const val MaxLines = 4000

        /**
         * 单条命令的上限。
         *
         * 这个终端是按行收发的（没有 pty），命令跑完才看得到输出；`pip install`
         * 一个大包一两分钟很正常，60 秒不够，再长则会让人以为卡死了。
         */
        const val CommandTimeoutSeconds = 300L
    }

    private val _lines = MutableStateFlow<List<TerminalLine>>(emptyList())
    val lines: StateFlow<List<TerminalLine>> = _lines.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _cwd = MutableStateFlow(Workspace.rootOrNull() ?: File("/"))
    val cwd: StateFlow<File> = _cwd.asStateFlow()

    /** 上一个目录，给 `cd -` 用。 */
    private var previousCwd: File? = null

    private val history = mutableListOf<String>()
    private var historyCursor = -1

    /**
     * 从外部（AI 工具）写一条命令记录。
     *
     * 与 [submit] 的区别：**不执行、不进 history、不需要 cwd** —— 命令已经在
     * 别处跑完了，这里只负责把它排成真终端的样子：`prompt 命令` 一行，
     * 输出逐行跟在后面。这样 AI 的记录和用户自己敲的命令在视觉上是同一种东西。
     */
    fun appendExternal(
        prompt: String,
        command: String,
        output: String?,
        isError: Boolean,
    ) {
        val block = buildList {
            add(TerminalLine("$prompt $command", TerminalLine.Kind.INPUT))
            output?.trimEnd()
                ?.takeIf { it.isNotBlank() }
                ?.lineSequence()
                ?.forEach {
                    add(
                        TerminalLine(
                            it,
                            if (isError) TerminalLine.Kind.ERROR else TerminalLine.Kind.OUTPUT,
                        ),
                    )
                }
        }
        _lines.value = (_lines.value + block).let {
            if (it.size <= MaxLines) it else it.subList(it.size - MaxLines, it.size)
        }
    }

    fun appendInfo(text: String) {
        _lines.value = _lines.value + TerminalLine(text, TerminalLine.Kind.INFO)
    }

    /**
     * 中断当前命令。
     *
     * 做不到真正的 SIGINT（子进程不是 tty 会话），但把界面从 busy 里放出来、
     * 并在输出里留一行说明，比"点了没反应"好 —— 用户至少知道该等它自己超时。
     */
    fun abort() {
        if (!_busy.value) return
        _busy.value = false
        appendInfo("（已请求中断；正在运行的子进程会在超时后回收）")
    }

    fun clear() {
        _lines.value = emptyList()
    }

    /** 提示符。相对工作区的路径更短，手机上不容易换行。 */
    fun prompt(): String {
        val base = Workspace.rootOrNull()
        val current = _cwd.value
        val shown = if (base != null && current.path.startsWith(base.path)) {
            "~" + current.path.removePrefix(base.path)
        } else {
            current.path
        }
        return "dsha:$shown$"
    }

    /** 上一条 / 下一条历史，供输入框的上下箭头用。 */
    fun historyUp(): String? {
        if (history.isEmpty()) return null
        historyCursor = (historyCursor - 1).coerceAtLeast(0)
        return history[historyCursor]
    }

    fun historyDown(): String? {
        if (history.isEmpty()) return null
        historyCursor = (historyCursor + 1).coerceAtMost(history.lastIndex)
        return history[historyCursor]
    }

    suspend fun submit(raw: String) {
        // 只读会话不接受输入：它是记录，不是 shell
        if (readOnly) return
        val command = raw.trim()
        if (command.isEmpty()) return
        history += command
        historyCursor = history.size

        _lines.value = _lines.value + TerminalLine("${prompt()} $command", TerminalLine.Kind.INPUT)

        if (command == "clear" || command == "cls") {
            clear()
            return
        }

        // `cd` 只能自己处理：子进程里的 chdir 不会影响下一次启动的进程
        if (command == "cd" || command.startsWith("cd ")) {
            val target = command.removePrefix("cd").trim().ifBlank { "~" }
            val next = when {
                // `~` 是工作区根，不是 $HOME：这个终端是「在工作区里干活」的，回家就该回到那儿
                target == "~" -> Workspace.rootOrNull() ?: _cwd.value
                target == "-" -> previousCwd ?: _cwd.value
                target.startsWith("~/") -> File(Workspace.rootOrNull() ?: _cwd.value, target.removePrefix("~/"))
                target.startsWith("/") -> File(target)
                else -> File(_cwd.value, target)
            }
            if (next.isDirectory) {
                previousCwd = _cwd.value
                _cwd.value = runCatching { next.canonicalFile }.getOrDefault(next)
            } else {
                _lines.value = _lines.value + TerminalLine("cd: 没有这个目录：$target", TerminalLine.Kind.ERROR)
            }
            return
        }

        _busy.value = true
        try {
            val result = Shell.run(command, _cwd.value, CommandTimeoutSeconds)
            val additions = buildList {
                result.stdout.trimEnd().takeIf { it.isNotBlank() }?.lineSequence()?.forEach {
                    add(TerminalLine(it, TerminalLine.Kind.OUTPUT))
                }
                result.stderr.trimEnd().takeIf { it.isNotBlank() }?.lineSequence()?.forEach {
                    add(TerminalLine(it, TerminalLine.Kind.ERROR))
                }
                if (result.timedOut) {
                    add(TerminalLine("（超时 $CommandTimeoutSeconds 秒，已终止）", TerminalLine.Kind.ERROR))
                }
                if (result.truncated) {
                    add(TerminalLine("（输出过长，已截断）", TerminalLine.Kind.INFO))
                }
            }
            _lines.value = _lines.value + additions
        } finally {
            _busy.value = false
        }
    }
}
