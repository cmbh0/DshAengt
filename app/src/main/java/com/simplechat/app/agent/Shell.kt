package com.simplechat.app.agent

import android.system.Os
import android.system.OsConstants
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 在本机执行 shell 命令。
 *
 * ### 跑在什么上面
 *
 * 首选 APK 自带的 musl 工具箱（[MuslEnv]）：静态链接的 BusyBox，`ash` 加两百来个
 * 命令，直接跑在手机内核上，没有容器、没有 ptrace 翻译层。装了 Python 的话
 * `python3` / `pip` 也在 `PATH` 里。
 *
 * 工具箱跑不起来的设备（32 位 CPU，或者自检没过）回落到系统的 `/system/bin/sh`
 * —— 能力少一截，但 `ls` / `cat` / `cp` 这些仍然能用，不至于整个 shell 没了。
 *
 * ### 两条硬约束
 *
 * - **必须有超时**。模型很容易写出 `tail -f`、`sleep 9999` 或等 stdin 的命令，
 *   没有超时这一轮对话就永远挂着。
 * - **输出要截断**。一条 `cat` 大文件能产出几 MB，全塞进上下文既贵又没用，
 *   头部通常已经足够模型判断。
 */
object Shell {

    private const val MaxOutputChars = 24_000

    data class Result(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val timedOut: Boolean,
        val truncated: Boolean,
    ) {
        fun asToolOutput(): String = buildString {
            append("exit=").append(exitCode)
            if (timedOut) append("（超时被终止）")
            signalHint(exitCode)?.let { append("（").append(it).append("）") }
            if (stdout.isNotBlank()) {
                append("\n--- stdout ---\n").append(stdout.trimEnd())
            }
            if (stderr.isNotBlank()) {
                append("\n--- stderr ---\n").append(stderr.trimEnd())
            }
            if (stdout.isBlank() && stderr.isBlank()) append("\n（无输出）")
            if (truncated) append("\n（输出过长，已截断）")
        }
    }

    /**
     * 退出码大于 128 说明进程是被信号杀掉的。几个常见的翻译成人话 ——
     * 模型看到「exit=137」多半会当成普通失败去重试，而那其实是内存不够。
     */
    internal fun signalHint(exitCode: Int): String? = when (exitCode) {
        137 -> "被 SIGKILL 终止，通常是内存不足或被系统回收"
        139 -> "段错误 SIGSEGV"
        143 -> "被 SIGTERM 终止"
        159 -> "被系统的 seccomp 过滤器拦下：用到了安卓不允许应用调用的系统调用"
        else -> null
    }

    /**
     * 执行一条命令。
     *
     * @param cwd 工作目录，默认工作区根。目录不存在时不会去建 —— 那多半是工作区
     *   被删了或权限丢了，悄悄建一个空目录只会把问题藏起来。
     */
    suspend fun run(
        command: String,
        cwd: File = Workspace.requireRoot(),
        timeoutSeconds: Long = 30,
    ): Result {
        // 首次调用要建目录、建软链、起一次自检进程，不能落在主线程上
        val musl = withContext(Dispatchers.IO) { MuslEnv.prepare() }
        return if (musl != null) {
            exec(
                argv = listOf(musl.busybox, "setsid", "sh", "-c", command),
                cwd = cwd,
                environment = musl.environment,
                timeoutSeconds = timeoutSeconds,
                ownGroup = true,
            )
        } else {
            exec(
                argv = listOf("/system/bin/sh", "-c", command),
                cwd = cwd,
                environment = mapOf(
                    "HOME" to cwd.absolutePath,
                    "TMPDIR" to cwd.absolutePath,
                    // 让多数 CLI 关掉交互式彩色输出，减少无意义控制字符
                    "TERM" to "dumb",
                    "NO_COLOR" to "1",
                ),
                timeoutSeconds = timeoutSeconds,
                ownGroup = false,
            )
        }
    }

    /**
     * 起一个进程并等它结束。
     *
     * @param ownGroup 进程自己是一个进程组的组长（命令是经 `setsid` 起的）。
     *   为真时超时 / 取消会把**整个组**杀掉 —— 只杀 `sh` 的话，它正在等的
     *   `sleep`、`python3` 会变成孤儿继续跑，占着 CPU 和文件锁。
     */
    internal suspend fun exec(
        argv: List<String>,
        cwd: File,
        environment: Map<String, String>,
        timeoutSeconds: Long,
        ownGroup: Boolean,
        dropFromEnvironment: Set<String> = MuslEnv.DroppedVariables,
    ): Result = withContext(Dispatchers.IO) {
        if (!cwd.isDirectory) {
            return@withContext failure("工作目录不存在或无法访问：${cwd.path}")
        }
        val process = try {
            ProcessBuilder(argv)
                .directory(cwd)
                .redirectErrorStream(false)
                .apply {
                    val env = environment()
                    dropFromEnvironment.forEach(env::remove)
                    env.putAll(environment)
                }
                .start()
        } catch (e: Exception) {
            return@withContext failure("无法启动 shell：${e.message}")
        }
        // 命令不读 stdin；不关掉的话 `cat`、`read` 这类会一直等到超时
        runCatching { process.outputStream.close() }

        // 读流必须并发，否则管道缓冲写满会互相等死
        val outReader = Reader(process.inputStream.bufferedReader(), MaxOutputChars)
        val errReader = Reader(process.errorStream.bufferedReader(), MaxOutputChars)
        val outThread = Thread(outReader, "shell-out").apply { isDaemon = true; start() }
        val errThread = Thread(errReader, "shell-err").apply { isDaemon = true; start() }

        /*
         * 超时或被取消时把整个组收掉；**正常结束不收**。
         *
         * 命令自己放到后台的东西（`python3 -m http.server &`）是模型故意留下的，
         * 下一条命令多半就要用它，跟着 shell 一起杀掉等于这个用法直接作废。
         */
        val group = if (ownGroup) pidOf(process) else null
        val finished = try {
            awaitProcess(process, timeoutSeconds)
        } catch (e: kotlinx.coroutines.CancellationException) {
            group?.let(::killGroup)
            throw e
        }
        if (!finished) group?.let(::killGroup)
        outThread.join(1_000)
        errThread.join(1_000)

        Result(
            stdout = outReader.text(),
            stderr = errReader.text(),
            exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1,
            timedOut = !finished,
            truncated = outReader.truncated || errReader.truncated,
        )
    }

    private fun failure(message: String) = Result(
        stdout = "",
        stderr = message,
        exitCode = -1,
        timedOut = false,
        truncated = false,
    )

    /**
     * 进程号。
     *
     * `Process.pid()` 要到很新的系统才有，而安卓的实现从一开始就把 pid 写在
     * `toString()` 里（`Process[pid=1234, hasExited=false]`）。认不出来就返回 null，
     * 调用方退回到只杀直接子进程。
     */
    internal fun pidOf(process: Process): Int? =
        Regex("pid=(\\d+)").find(process.toString())?.groupValues?.get(1)?.toIntOrNull()

    private fun killGroup(pid: Int) {
        // 负数 = 整个进程组。组已经空了会抛 ESRCH，正是想要的结果
        runCatching { Os.kill(-pid, OsConstants.SIGKILL) }
    }

    private class Reader(private val source: java.io.BufferedReader, private val limit: Int) : Runnable {
        private val buffer = StringBuilder()
        @Volatile var truncated = false

        override fun run() {
            runCatching {
                val chunk = CharArray(4096)
                while (true) {
                    val read = source.read(chunk)
                    if (read <= 0) break
                    synchronized(buffer) {
                        if (buffer.length < limit) {
                            val room = limit - buffer.length
                            buffer.append(chunk, 0, minOf(read, room))
                            if (read > room) truncated = true
                        } else {
                            truncated = true
                        }
                    }
                }
            }
        }

        fun text(): String = synchronized(buffer) { buffer.toString() }
    }
}
