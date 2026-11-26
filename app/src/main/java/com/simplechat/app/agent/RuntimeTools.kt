package com.simplechat.app.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 本机运行环境（[MuslEnv] + [MuslPython]）附带的工具。
 *
 * shell 本身是 `run_shell`（见 [BuiltinTools]），跑 Python 是 `python`，这里只剩
 * 「装包」这一件 —— 它值得单独一个工具，是因为模型自己敲 `pip install` 时
 * 既不知道解释器装没装，也分不清「网络不通」和「这个包需要编译器」。
 *
 * 2.4.x 的 `linux_shell` / `linux_env` 随 proot + Ubuntu 一起下线了：
 * 现在只有一套 shell，命令看到的就是真实的文件系统，不存在「该用哪个」的问题。
 */
object RuntimeTools {

    fun available(): Boolean = MuslEnv.supported()

    fun all(): List<AgentTool> = listOf(Install)

    private val PackageName = Regex("[A-Za-z0-9._@/:+=~<>!\\[\\],-]+")

    private object Install : AgentTool {
        override val name = "install_packages"
        override val description =
            "用 pip 给本机的 Python 安装第三方包。纯 Python 的包都能装；带 C 扩展的包只要发布了 " +
                "musllinux 的 wheel 也能装（numpy / pandas / pillow / lxml 等主流包都有）。" +
                "需要现场编译的包装不了 —— 这里没有编译器。本机没有 apt 和 npm。" +
                "装完用 python 工具 import 一下确认，再继续后面的步骤。"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("packages") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "包名，可以带版本约束，如 requests、numpy==2.1.0")
                }
            }
            putJsonArray("required") { add("packages") }
        }
        override val mutating = true
        override val risk = ToolRisk.PRIVILEGED

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
            // 旧版有 kind=apt/pip/npm。还带着它的调用给一句明白话，而不是当成 pip 去装一个不存在的包
            val kind = args.str("kind")?.lowercase()
            if (kind != null && kind != "pip") {
                return ToolOutcome.fail(
                    "本机环境没有 $kind：它是 App 自带的 BusyBox 加按需安装的 Python，不是一个发行版。" +
                        "系统命令用 run_shell 里现成的（grep / sed / awk / tar / wget …）；Python 包用本工具（不带 kind）。" +
                        "确实需要 $kind 的任务，请用户绑定云端工作环境。",
                )
            }
            val raw = args["packages"] as? JsonArray ?: return ToolOutcome.fail("缺少 packages 数组")
            val packages = raw.mapNotNull {
                (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty)
            }
            if (packages.isEmpty()) return ToolOutcome.fail("packages 是空的")
            // 包名要拼进命令行，挡掉 shell 元字符 —— 否则等于给了任意命令执行
            val invalid = packages.firstOrNull { !it.matches(PackageName) || it.startsWith("-") }
            if (invalid != null) return ToolOutcome.fail("包名含非法字符：$invalid")

            if (!MuslPython.isInstalled()) {
                return ToolOutcome.fail(
                    "这台手机上还没有装 Python。请让用户到「设置 → 运行环境」点一次「安装 Python」（下载约 28 MB），装好后再重试。",
                )
            }

            val joined = packages.joinToString(" ") { "'$it'" }
            // --prefer-binary：有 wheel 的旧版本优先于只有源码包的新版本，后者在这里多半编不出来
            val result = Shell.run(
                "python3 -m pip install --prefer-binary $joined",
                cwd = MuslEnv.homeDir(),
                timeoutSeconds = 600,
            )
            val body = buildString {
                if (result.stdout.isNotBlank()) append(tail(result.stdout))
                if (result.stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n[stderr]\n")
                    append(tail(result.stderr))
                }
                if (isEmpty()) append("（命令结束，没有输出）")
                if (result.timedOut) append("\n\n[超时被终止]")
                if (result.exitCode != 0 && !result.timedOut) append("\n\n[退出码 ${result.exitCode}]")
            }
            val shown = packages.joinToString(" ")
            return if (result.exitCode == 0 && !result.timedOut) {
                ToolOutcome.ok("已安装 $shown\n\n$body")
            } else {
                ToolOutcome.Failed("安装失败（$shown）\n\n$body${hint(result.stdout + result.stderr)}")
            }
        }

        /** pip 的输出前面全是下载进度，有用的在末尾。 */
        private fun tail(text: String): String {
            val trimmed = text.trimEnd()
            return if (trimmed.length <= 4_000) trimmed else "…\n" + trimmed.takeLast(4_000)
        }
    }

    /** 把 pip 最常见的两种失败翻译成下一步该做什么。 */
    internal fun hint(output: String): String = when {
        NeedsCompiler.containsMatchIn(output) ->
            "\n\n提示：这个包（或它的某个依赖）只有源码包，需要编译器才能装，本机装不了。" +
                "换一个有 musllinux wheel 的版本或替代库，或者请用户改用云端工作环境。"
        "No matching distribution" in output || "Could not find a version" in output ->
            "\n\n提示：包名或版本不对，或者它没有适用于 Python ${MuslPython.Version} / musllinux 的发行文件。"
        NetworkTrouble.containsMatchIn(output) ->
            "\n\n提示：像是网络问题（连不上软件源或超时），请用户检查网络后重试。"
        else -> ""
    }

    private val NeedsCompiler = Regex(
        "Failed building wheel|error: command '.*(gcc|cc|clang)'|No such file or directory: '(gcc|cc)'" +
            "|metadata-generation-failed|Unknown compiler|can't find Rust compiler|subprocess-exited-with-error",
    )

    private val NetworkTrouble = Regex(
        "ReadTimeoutError|ConnectTimeoutError|NewConnectionError|Temporary failure in name resolution" +
            "|Name does not resolve|Connection reset|Max retries exceeded",
    )
}
