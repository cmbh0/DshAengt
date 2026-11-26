package com.simplechat.app.agent

import java.io.File

/**
 * Python 运行时。
 *
 * 三条路，按优先级：
 *
 * 1. **Chaquopy**（若构建时接入了）：进程内 CPython。这里**全部用反射**调用 ——
 *    即使构建里没有 Chaquopy，这个文件也能正常编译，功能自动降级。
 * 2. **内置环境里的 CPython**（[MuslPython]）：在「设置 → 运行环境」里装一次，
 *    之后 `python3` / `pip` 在 shell 里直接可用。这是正路。
 * 3. **系统 python3**：个别 ROM 自带。
 *
 * 三条都不通时，`python` 工具返回一句人话，告诉模型该让用户去装 ——
 * 而不是抛异常把整轮对话打断。
 */
object PyRuntime {

    enum class Kind {
        /** 嵌入式解释器可用。 */
        EMBEDDED,

        /**
         * 内置环境里的 CPython（[MuslPython]）。
         *
         * 为什么不直接让模型在 `run_shell` 里敲：它每次都要写
         * `python3 - <<'EOF' … EOF`，引号与 heredoc 一错就是「命令没输出」，
         * 再猜两轮。一个结构化工具把这段噪音全省掉。
         */
        MUSL,

        /** 系统里有 python3 可执行文件（部分 ROM / Termux）。 */
        SYSTEM,

        /** 都没有。 */
        NONE,
    }

    @Volatile
    private var cachedKind: Kind? = null

    fun kind(): Kind {
        cachedKind?.let { return it }
        val detected = when {
            embedded() != null -> Kind.EMBEDDED
            MuslPython.isInstalled() && MuslEnv.health().ok -> Kind.MUSL
            systemPython() != null -> Kind.SYSTEM
            else -> Kind.NONE
        }
        cachedKind = detected
        return detected
    }

    fun isAvailable(): Boolean = kind() != Kind.NONE

    /** 装了或卸了解释器之后调一次：下次 [kind] 重新探测。 */
    fun invalidate() {
        cachedKind = null
    }

    fun displayName(): String = when (kind()) {
        Kind.EMBEDDED -> "内置 Python"
        Kind.MUSL -> "Python ${MuslPython.installedVersion().orEmpty()}".trim()
        Kind.SYSTEM -> "系统 Python"
        Kind.NONE -> "未安装"
    }

    /** 版本号，取不到就给空串（UI 上显示成"—"）。 */
    fun version(): String = when (kind()) {
        Kind.EMBEDDED -> runCatching {
            (embedded()?.let { callModule(it, "__main__") }?.let { readGlobal(it, "_v") } ?: "").trim()
        }.getOrDefault("")
        Kind.MUSL -> MuslPython.installedVersion()?.let { "Python $it" }.orEmpty()
        Kind.SYSTEM -> runCatching {
            val out = ProcessBuilder(systemPython()!!, "--version").redirectErrorStream(true).start()
            out.inputStream.bufferedReader().readText().trim()
        }.getOrDefault("")
        Kind.NONE -> ""
    }

    /**
     * 执行一段代码，返回 stdout。
     *
     * 嵌入式路径把 stdout 重定向到内存缓冲 —— 不重定向的话 `print` 会进 logcat，
     * 模型拿到的回执永远是空的，它会以为代码没跑。
     */
    suspend fun run(code: String, cwd: File): ToolOutcome {
        if (code.isBlank()) return ToolOutcome.fail("代码为空")
        return when (kind()) {
            Kind.EMBEDDED -> runEmbedded(code)
            Kind.MUSL -> runMusl(code, cwd)
            Kind.SYSTEM -> runSystem(code, cwd)
            Kind.NONE -> ToolOutcome.fail(
                "这台手机上还没有装 Python。请让用户到「设置 → 运行环境」点一次「安装 Python」" +
                    "（下载约 28 MB）；装好之前可以先用 run_shell 里的 awk / sed / bc 之类处理。",
            )
        }
    }

    // ── 嵌入式 ────────────────────────────────────────────────

    private fun runEmbedded(code: String): ToolOutcome = runCatching {
        val py = embedded() ?: return ToolOutcome.fail("内置解释器未就绪")
        val main = callModule(py, "__main__")
        // 用一个独立的命名空间跑，避免用户代码里的变量污染 __main__
        callAttr(main, "exec", preamble(code))
        val out = readGlobal(main, "_dsha_out").orEmpty()
        val err = readGlobal(main, "_dsha_err").orEmpty()
        val text = buildString {
            if (out.isNotBlank()) append(out)
            if (err.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append("--- stderr ---\n").append(err)
            }
            if (isEmpty()) append("（无输出）")
        }
        ToolOutcome.ok(text)
    }.getOrElse { ToolOutcome.fail("Python 执行失败：${it.message}") }

    /** 把用户代码包进捕获 stdout/stderr 的壳里。 */
    private fun preamble(code: String): String {
        val escaped = code.replace("\\", "\\\\").replace("\"\"\"", "\\\"\\\"\\\"")
        return buildString {
            appendLine("import io as _io, sys as _sys, traceback as _tb")
            appendLine("_dsha_buf = _io.StringIO()")
            appendLine("_dsha_errbuf = _io.StringIO()")
            appendLine("_dsha_src = \"\"\"$escaped\"\"\"")
            appendLine("_dsha_ns = {'__name__': '__main__'}")
            appendLine("_o, _e = _sys.stdout, _sys.stderr")
            appendLine("_sys.stdout, _sys.stderr = _dsha_buf, _dsha_errbuf")
            appendLine("try:")
            appendLine("    exec(compile(_dsha_src, '<dsha>', 'exec'), _dsha_ns)")
            appendLine("except Exception:")
            appendLine("    _dsha_errbuf.write(_tb.format_exc())")
            appendLine("finally:")
            appendLine("    _sys.stdout, _sys.stderr = _o, _e")
            appendLine("_dsha_out = _dsha_buf.getvalue()")
            appendLine("_dsha_err = _dsha_errbuf.getvalue()")
        }
    }

    private fun embedded(): Any? = runCatching {
        val cls = Class.forName("com.chaquo.python.Python")
        cls.getMethod("getInstance").invoke(null)
    }.getOrNull()

    private fun callModule(python: Any, name: String): Any = python.javaClass
        .getMethod("getModule", String::class.java)
        .invoke(python, name)

    /**
     * 调一次 Python 侧的 `callAttr(name, ...)`。
     *
     * 走反射而不是直接调：Chaquopy 存在与否是构建期决定的，这里不能对它产生
     * 编译期依赖 —— 否则"没接 Chaquopy 的构建"整个编不过，而它只是可选加速。
     */
    private fun callAttr(target: Any, name: String, vararg args: Any?): Any? = runCatching {
        val method = target.javaClass.methods.firstOrNull {
            it.name == "callAttr" && it.parameterTypes.isNotEmpty() &&
                it.parameterTypes[0] == String::class.java
        } ?: return@runCatching null
        method.invoke(target, name, *args)
    }.getOrNull()

    private fun readGlobal(module: Any, name: String): String? = runCatching {
        val obj = module.javaClass.getMethod("get", String::class.java).invoke(module, name)
        obj?.toString()
    }.getOrNull()

    // ── 内置环境 ──────────────────────────────────────────────

    /**
     * 用内置环境里的 CPython 跑一段代码。
     *
     * 代码落成临时文件再执行，而不是 `python3 -c "..."`：引号、换行、中文都不需要
     * 转义，出错时行号也是对的。临时文件放在 App 自己的缓存目录 —— 不往工作区里
     * 丢 `.dsha/tmp_*.py`，那是用户的目录（绑的是共享存储时还会被别的 App 看见）。
     */
    private suspend fun runMusl(code: String, cwd: File): ToolOutcome {
        val script = File(MuslEnv.tmpDir().apply { mkdirs() }, "py_${System.nanoTime()}.py")
        return runCatching {
            script.writeText(code)
            val result = Shell.run(
                command = "python3 '${script.absolutePath}'",
                cwd = cwd,
                timeoutSeconds = 120,
            )
            if (result.exitCode == 0 && !result.timedOut) {
                ToolOutcome.ok(
                    buildString {
                        append(result.stdout.ifBlank { "（无输出）" })
                        // 警告类的 stderr 不算失败，但模型应该看得见
                        if (result.stderr.isNotBlank()) append("\n--- stderr ---\n").append(result.stderr.trimEnd())
                        if (result.truncated) append("\n（输出过长，已截断）")
                    },
                )
            } else {
                ToolOutcome.fail(
                    buildString {
                        append("exit=").append(result.exitCode)
                        if (result.timedOut) append("（超时 120 秒被终止）")
                        Shell.signalHint(result.exitCode)?.let { append("（").append(it).append("）") }
                        append('\n')
                        if (result.stdout.isNotBlank()) append(result.stdout).append('\n')
                        if (result.stderr.isNotBlank()) append(result.stderr)
                    }.trim(),
                )
            }
        }.getOrElse { ToolOutcome.fail("Python 执行失败：${it.message}") }
            .also { script.delete() }
    }

    // ── 系统 ──────────────────────────────────────────────────

    private fun runSystem(code: String, cwd: File): ToolOutcome = runCatching {
        val py = systemPython() ?: return ToolOutcome.fail("系统 Python 不可用")
        val dir = File(cwd, ".dsha").apply { mkdirs() }
        val script = File(dir, "tmp_${System.nanoTime()}.py").apply { writeText(code) }
        try {
            val result = kotlinx.coroutines.runBlocking { Shell.run("$py ${script.absolutePath}", cwd, 60) }
            if (result.exitCode == 0) {
                ToolOutcome.ok(result.stdout.ifBlank { "（无输出）" })
            } else {
                ToolOutcome.fail(
                    buildString {
                        append("exit=").append(result.exitCode).append('\n')
                        if (result.stdout.isNotBlank()) append(result.stdout).append('\n')
                        if (result.stderr.isNotBlank()) append(result.stderr)
                    }.trim(),
                )
            }
        } finally {
            script.delete()
        }
    }.getOrElse { ToolOutcome.fail("Python 执行失败：${it.message}") }

    /** 找系统 python3：先看常见绝对路径，再问 `command -v`。 */
    private fun systemPython(): String? {
        val candidates = listOf(
            "/system/bin/python3",
            "/usr/bin/python3",
            "/data/data/com.termux/files/usr/bin/python3",
        )
        candidates.firstOrNull { File(it).canExecute() }?.let { return it }
        return runCatching {
            val process = ProcessBuilder("/system/bin/sh", "-c", "command -v python3")
                .redirectErrorStream(true)
                .start()
            val path = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            path.takeIf { it.startsWith("/") && File(it).canExecute() }
        }.getOrNull()
    }
}
