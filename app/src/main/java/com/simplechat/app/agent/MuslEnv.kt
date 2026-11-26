package com.simplechat.app.agent

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.Os
import com.simplechat.app.util.AppLog
import java.io.File
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * 本机运行环境：随 APK 分发的 musl 工具箱。
 *
 * ### 它是什么
 *
 * `jniLibs/<abi>/` 里三个静态 / 自带加载器的 ELF（构建脚本与补丁在 `tools/musl/`）：
 *
 * | 文件 | 作用 |
 * |---|---|
 * | `libbusybox.so` | BusyBox：`ash` 加两百来个命令（grep / sed / awk / tar / wget / vi …） |
 * | `libmusl.so` | musl 的动态加载器兼 libc，用来启动按需下载的 CPython（见 [MuslPython]） |
 * | `libdshaexec.so` | `python3` / `pip` 的入口，把调用转给 `libmusl.so` |
 *
 * 它们直接跑在手机内核上。**没有 rootfs、没有 proot、没有 ptrace 翻译层** ——
 * 命令看到的就是真实的文件系统，`cd` 就是 `chdir`，工作区路径在 shell 里和在
 * 文件工具里是同一个字符串。
 *
 * ### 为什么文件名是 lib*.so
 *
 * 安卓 10 起应用不能 `execve` 自己私有目录里的文件，只有安装时解到
 * `nativeLibraryDir` 的东西可以，而系统只解 `lib*.so` 这种名字。它们并不是
 * 动态库，只是借这个名字落到能执行的地方（所以 `extractNativeLibs` 与
 * `useLegacyPackaging` 必须开着）。
 *
 * ### 安卓上缺的东西，这里补
 *
 * 静态程序不会去问系统的 DNS 服务，安卓也没有 `/etc`。`tools/musl` 里那份 musl
 * 认一个 `MUSL_ETC` 环境变量：`resolv.conf` / `hosts` / `passwd` / `group` 都从
 * 那个目录读。本类负责把这几个文件写好、并在每次起命令前按当前网络刷新 DNS。
 *
 * ### 状态放在哪
 *
 * ```
 * files/musl/bin    命令软链（指向 nativeLibraryDir，App 升级后路径会变，要重建）
 * files/musl/etc    resolv.conf、hosts、passwd、group、cacert.pem
 * files/musl/home   $HOME：pip 配置、shell 历史、用户自己放的东西
 * files/musl/python 按需下载的 CPython
 * cache/musl-tmp    $TMPDIR
 * ```
 */
object MuslEnv {

    private const val Tag = "MuslEnv"

    const val BusyBoxVersion = "1.37.0"
    const val MuslVersion = "1.2.5"

    /**
     * 从 App 进程继承来、但不能传给子进程的变量。
     *
     * `LD_PRELOAD` / `LD_LIBRARY_PATH` 两套加载器（bionic 与 musl）都认：个别 ROM
     * 给应用进程预设了它们，带进 musl 的 Python 就是去加载一个 bionic 的库，当场崩。
     */
    val DroppedVariables: Set<String> = setOf("LD_PRELOAD", "LD_LIBRARY_PATH")

    /** 一次命令执行要用的东西：可执行文件路径 + 环境变量。 */
    class Session(val busybox: String, val environment: Map<String, String>)

    /** 自检结果。[ok] 为假时 [detail] 是给人看的原因。 */
    data class Health(val ok: Boolean, val detail: String)

    private val lock = Any()

    /** 本进程里已经为哪个 nativeLibraryDir 建好了软链。 */
    @Volatile
    private var preparedFor: String? = null

    @Volatile
    private var health: Health? = null

    @Volatile
    private var lastResolvConf: String? = null

    // ── 路径 ─────────────────────────────────────────────────

    private fun context(): Context? = Workspace.appContextOrNull()

    private fun nativeDir(): File? = context()?.applicationInfo?.nativeLibraryDir?.let(::File)

    fun busybox(): File? = nativeDir()?.let { File(it, "libbusybox.so") }

    fun loader(): File? = nativeDir()?.let { File(it, "libmusl.so") }

    fun launcher(): File? = nativeDir()?.let { File(it, "libdshaexec.so") }

    fun baseDir(): File = File(Workspace.appDir(), "musl")

    fun binDir(): File = File(baseDir(), "bin")

    fun etcDir(): File = File(baseDir(), "etc")

    fun homeDir(): File = File(baseDir(), "home")

    fun tmpDir(): File = File(context()?.cacheDir ?: baseDir(), "musl-tmp")

    // ── 能不能用 ─────────────────────────────────────────────

    /** 这台设备上有没有对应架构的工具箱。只看文件在不在，不起进程。 */
    fun supported(): Boolean = busybox()?.isFile == true

    /** 不支持时给一句人话；支持则返回 null。 */
    fun unsupportedReason(): String? {
        if (supported()) return null
        val abis = Build.SUPPORTED_ABIS.joinToString(" / ")
        return "这台设备的 CPU 架构（$abis）没有对应的内置工具箱，目前只支持 arm64-v8a 与 x86_64。" +
            "shell 会退回系统自带的 sh，Python 不可用。"
    }

    /** 给界面显示的版本串。 */
    fun versionLabel(): String = "BusyBox $BusyBoxVersion · musl $MuslVersion"

    /**
     * 自检：真的起一次 BusyBox，看它能不能跑完一条最简单的命令。
     *
     * 结果在进程内缓存。**只有它过了，[Shell] 才走工具箱**；否则退回系统 sh ——
     * 宁可少一截能力，也不能让每条命令都死在同一个地方。
     */
    fun health(refresh: Boolean = false): Health {
        if (!refresh) health?.let { return it }
        val result = probe()
        health = result
        if (!result.ok) AppLog.w(Tag, "工具箱自检未通过：${result.detail}")
        return result
    }

    private fun probe(): Health {
        val binary = busybox() ?: return Health(false, "App 还没初始化完")
        if (!binary.isFile) return Health(false, unsupportedReason() ?: "找不到 ${binary.name}")
        return try {
            val process = ProcessBuilder(binary.absolutePath, "sh", "-c", "echo dsha-ok")
                .redirectErrorStream(true)
                .apply { DroppedVariables.forEach(environment()::remove) }
                .start()
            runCatching { process.outputStream.close() }
            val output = process.inputStream.bufferedReader().readText().trim()
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return Health(false, "自检命令 15 秒没有返回")
            }
            val code = process.exitValue()
            when {
                code == 0 && output == "dsha-ok" -> Health(true, versionLabel())
                else -> Health(
                    false,
                    buildString {
                        append("自检命令退出码 ").append(code)
                        Shell.signalHint(code)?.let { append("（").append(it).append("）") }
                        if (output.isNotBlank()) append("：").append(output.take(200))
                    },
                )
            }
        } catch (e: Exception) {
            Health(false, "起不来：${e.message}")
        }
    }

    // ── 准备 ─────────────────────────────────────────────────

    /**
     * 准备好环境并返回一次执行要用的东西；工具箱不可用时返回 null。
     *
     * 幂等且便宜：目录与软链只在首次（或 App 升级后）建一次，之后每次调用
     * 只是刷新 DNS 并拼一张环境变量表。
     */
    fun prepare(): Session? {
        if (!supported()) return null
        if (!health().ok) return null
        val binary = busybox() ?: return null
        return runCatching {
            ensureLayout()
            refreshResolvConf()
            Session(binary.absolutePath, environment())
        }.onFailure { AppLog.w(Tag, "准备运行环境失败", it) }.getOrNull()
    }

    /** 建目录、建命令软链、写 etc 下的静态文件。 */
    fun ensureLayout() {
        val native = nativeDir()?.absolutePath ?: error("App 还没初始化完")
        if (preparedFor == native) return
        synchronized(lock) {
            if (preparedFor == native) return
            listOf(binDir(), etcDir(), homeDir(), tmpDir()).forEach { it.mkdirs() }
            linkCommands(native)
            writeStaticEtc()
            writeCaBundle()
            writePipConfig()
            preparedFor = native
        }
    }

    /**
     * 命令软链：`bin/<applet>` → `libbusybox.so`，`bin/python3` 等 → `libdshaexec.so`。
     *
     * `ash` 自己不需要它们（编译时开了 standalone，applet 名直接可用），
     * 需要的是**别的程序**：`tar czf` 要 exec 一个叫 gzip 的东西，
     * Python 的 `subprocess.run(["grep", ...])` 要在 PATH 里找到 grep。
     *
     * 软链的目标是 nativeLibraryDir 下的绝对路径，而那个目录每次安装 / 升级都换名字。
     * 所以把目标路径记在 `.target` 里，对不上就整批重建。
     */
    private fun linkCommands(native: String) {
        val bin = binDir()
        val marker = File(bin, ".target")
        val stamp = "$native\n${BusyBoxVersion}\n"
        if (marker.isFile && runCatching { marker.readText() }.getOrNull() == stamp &&
            File(bin, "sh").exists()
        ) {
            return
        }
        bin.listFiles()?.forEach { runCatching { it.delete() } }

        val busybox = File(native, "libbusybox.so").absolutePath
        val process = ProcessBuilder(busybox, "--install", "-s", bin.absolutePath)
            .redirectErrorStream(true)
            .apply { DroppedVariables.forEach(environment()::remove) }
            .start()
        runCatching { process.outputStream.close() }
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly()
        if (!File(bin, "sh").exists()) {
            error("建立命令软链失败：${output.take(300).ifBlank { "busybox --install 没有产出" }}")
        }

        val launcher = File(native, "libdshaexec.so")
        if (launcher.isFile) {
            PythonCommands.forEach { name ->
                val link = File(bin, name)
                runCatching { link.delete() }
                runCatching { Os.symlink(launcher.absolutePath, link.absolutePath) }
                    .onFailure { AppLog.w(Tag, "建软链失败：$name", it) }
            }
        }
        marker.writeText(stamp)
    }

    private val PythonCommands = listOf("python3", "python", "pip3", "pip")

    /** 安卓的用户名约定：应用 uid 10234 → `u0_a234`。只是给 `whoami` / `ls -l` 一个名字。 */
    internal fun userName(uid: Int): String {
        val user = uid / 100_000
        val app = uid % 100_000
        return if (app >= 10_000) "u${user}_a${app - 10_000}" else "u${user}_$app"
    }

    private fun writeStaticEtc() {
        val etc = etcDir()
        val uid = android.os.Process.myUid()
        val name = userName(uid)
        writeIfChanged(File(etc, "hosts"), "127.0.0.1 localhost\n::1 localhost ip6-localhost\n")
        writeIfChanged(
            File(etc, "passwd"),
            "root:x:0:0:root:/:/system/bin/sh\n" +
                "$name:x:$uid:$uid:DSHA:${homeDir().absolutePath}:${File(binDir(), "sh").absolutePath}\n",
        )
        writeIfChanged(File(etc, "group"), "root:x:0:\n$name:x:$uid:\ninet:x:3003:$name\n")
    }

    /**
     * 把系统信任的根证书拼成一个 PEM，交给 `SSL_CERT_FILE`。
     *
     * CPython 自带的 OpenSSL 默认去 `/etc/ssl` 找证书，安卓上没有，于是
     * `urllib.request.urlopen("https://…")` 一律 `CERTIFICATE_VERIFY_FAILED`。
     * 系统的证书目录是按**旧式**主题哈希命名的，OpenSSL 3 不认那套文件名，
     * 所以不能直接当 `SSL_CERT_DIR` 用，只能拼成单文件。
     *
     * pip 与 requests 用的是自带的 certifi，不受影响；这里照顾的是标准库。
     */
    private fun writeCaBundle() {
        val target = File(etcDir(), "cacert.pem")
        val source = listOf(
            "/apex/com.android.conscrypt/cacerts",
            "/system/etc/security/cacerts",
        ).map(::File).firstOrNull { it.isDirectory } ?: return
        val files = source.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return
        // 证书数量与目录时间都没变就不重拼：一百多个文件，每次启动读一遍不值得
        val stamp = "${source.path}:${files.size}:${source.lastModified()}"
        val marker = File(etcDir(), ".cacert-stamp")
        if (target.length() > 0 && runCatching { marker.readText() }.getOrNull() == stamp) return

        runCatching {
            val temp = File(target.path + ".tmp")
            temp.bufferedWriter().use { out ->
                files.forEach { file ->
                    val pem = extractPem(runCatching { file.readText() }.getOrDefault(""))
                    if (pem.isNotEmpty()) out.write(pem)
                }
            }
            if (temp.length() > 0 && temp.renameTo(target)) marker.writeText(stamp) else temp.delete()
        }.onFailure { AppLog.w(Tag, "生成根证书文件失败", it) }
    }

    /** 只留 BEGIN…END 之间的部分：系统证书文件后面往往还跟着一段人读的文本转储。 */
    internal fun extractPem(text: String): String {
        val out = StringBuilder()
        var inside = false
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("-----BEGIN CERTIFICATE") -> { inside = true; out.append(line).append('\n') }
                line.startsWith("-----END CERTIFICATE") -> {
                    if (inside) out.append(line).append('\n')
                    inside = false
                }
                inside && line.isNotEmpty() -> out.append(line).append('\n')
            }
        }
        return out.toString()
    }

    /**
     * pip 的默认源。
     *
     * 直连 pypi.org 在国内基本是读超时，而 pip 的报错是一大段重试日志，模型读完
     * 也只能再试一次。只在文件**不存在**时写：用户改过就尊重他的。
     */
    private fun writePipConfig() {
        val file = File(homeDir(), ".config/pip/pip.conf")
        if (file.exists()) return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                """
                [global]
                index-url = https://pypi.tuna.tsinghua.edu.cn/simple
                disable-pip-version-check = true
                """.trimIndent() + "\n",
            )
        }
    }

    /**
     * 按当前网络写 `resolv.conf`。
     *
     * 系统给的 DNS 排前面（内网域名、运营商劫持的解析都靠它），后面垫两个公共
     * DNS 兜底 —— 开着「私人 DNS」时系统报的服务器只接 DoT，明文 53 端口问不通。
     */
    private fun refreshResolvConf() {
        val servers = LinkedHashSet<String>()
        runCatching {
            val manager = context()?.getSystemService(ConnectivityManager::class.java)
            val network = manager?.activeNetwork
            manager?.getLinkProperties(network)?.dnsServers?.forEach { address ->
                // 链路本地的 IPv6 地址离了网卡名没法用，写进去只会让每次解析先白等一轮超时
                if (address.isLinkLocalAddress) return@forEach
                address.hostAddress?.takeIf { it.isNotBlank() && '%' !in it }?.let(servers::add)
            }
        }
        val text = resolvConf(servers.toList())
        if (text == lastResolvConf) return
        writeIfChanged(File(etcDir(), "resolv.conf"), text)
        lastResolvConf = text
    }

    /**
     * musl 最多认 3 个 nameserver，多写的会被忽略。
     *
     * 系统给的最多占两席，**第三席永远留给公共 DNS**：系统报了三四个服务器
     * （双栈网络很常见）又恰好都不接明文查询时，没有兜底就是彻底解析不了。
     */
    internal fun resolvConf(system: List<String>): String {
        val servers = (system.distinct().take(2) + FallbackDns).distinct().take(3)
        return buildString {
            servers.forEach { append("nameserver ").append(it).append('\n') }
            append("options timeout:3 attempts:2\n")
        }
    }

    private val FallbackDns = listOf("223.5.5.5", "119.29.29.29", "8.8.8.8")

    private fun writeIfChanged(file: File, text: String) {
        if (file.isFile && runCatching { file.readText() }.getOrNull() == text) return
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    // ── 环境变量 ─────────────────────────────────────────────

    /** 起命令时在继承来的环境之上覆盖的变量。 */
    fun environment(): Map<String, String> {
        val bin = binDir().absolutePath
        val home = homeDir().absolutePath
        val busybox = busybox()?.absolutePath.orEmpty()
        val python = MuslPython.home().takeIf { MuslPython.isInstalled() }
        val name = userName(android.os.Process.myUid())

        val path = buildList {
            add(bin)
            // pip 装出来的命令行入口（pytest、black …）。它们是带 shebang 的脚本，
            // 不能直接 exec，但 ash 认得 —— 见 tools/musl 里 BusyBox 的补丁
            python?.let { add(File(it, "bin").absolutePath) }
            add("$home/.local/bin")
            add("/system/bin")
            add("/system/xbin")
            add("/vendor/bin")
        }.joinToString(":")

        return buildMap {
            put("PATH", path)
            put("HOME", home)
            put("TMPDIR", tmpDir().absolutePath)
            put("SHELL", "$bin/sh")
            put("USER", name)
            put("LOGNAME", name)
            put("LANG", "C.UTF-8")
            put("TZ", posixTimeZone(TimeZone.getDefault().getOffset(System.currentTimeMillis())))
            // 让多数 CLI 关掉交互式彩色输出，减少无意义控制字符
            put("TERM", "dumb")
            put("NO_COLOR", "1")
            put("MUSL_ETC", etcDir().absolutePath)
            put("MUSL_SHELL", busybox)
            put("DSHA_BIN", bin)
            put("PIP_CACHE_DIR", File(context()?.cacheDir ?: tmpDir(), "pip").absolutePath)
            python?.let { put("DSHA_PYTHON_HOME", it.absolutePath) }
            File(etcDir(), "cacert.pem").takeIf { it.length() > 0 }?.let {
                put("SSL_CERT_FILE", it.absolutePath)
            }
        }
    }

    /**
     * POSIX 写法的时区（`<+08>-8`）。
     *
     * musl 认 `Asia/Shanghai` 这种名字要去 `/usr/share/zoneinfo` 查表，安卓没有；
     * 不给 TZ 则一律 UTC，`date` 和 Python 的 `datetime.now()` 都会差出一个时区。
     * 只取**当前**偏移，不带夏令时规则：一条命令跑不过一次换季。
     *
     * 注意 POSIX 的符号是反的：东八区写成 `-8`。
     */
    internal fun posixTimeZone(offsetMillis: Int): String {
        val totalMinutes = offsetMillis / 60_000
        val sign = if (totalMinutes < 0) '-' else '+'
        val hours = kotlin.math.abs(totalMinutes) / 60
        val minutes = kotlin.math.abs(totalMinutes) % 60
        val name = buildString {
            append('<').append(sign).append(hours.toString().padStart(2, '0'))
            if (minutes != 0) append(minutes.toString().padStart(2, '0'))
            append('>')
        }
        val posixSign = if (totalMinutes < 0) "" else "-"
        val offset = buildString {
            append(posixSign).append(hours)
            if (minutes != 0) append(':').append(minutes.toString().padStart(2, '0'))
        }
        return if (totalMinutes == 0) "UTC0" else name + offset
    }

    // ── 旧版 Ubuntu 环境 ─────────────────────────────────────

    /**
     * 2.4.x 及更早装过的 proot + Ubuntu 根文件系统。
     *
     * 新版不再使用它，但**不自动删**：里面的 `/root` 可能有用户让 AI 留下的文件，
     * 几百 MB 说没就没不是升级该做的事。运行环境页会显示它占了多少，由用户点清理。
     */
    fun legacyUbuntuDir(): File? = File(Workspace.appDir(), "ubuntu").takeIf { it.isDirectory }

    fun legacyUbuntuBytes(): Long = legacyUbuntuDir()?.let(::sizeOf) ?: 0L

    /**
     * 删掉旧环境。
     *
     * 不用 `deleteRecursively()`：rootfs 里有指向 `/` 的符号链接、只读目录和
     * 悬空链接，Kotlin 那个实现遇到第一个删不掉的就整体报失败。交给 `rm -rf`
     * （先把权限放开），它不跟随链接，也不在乎目标存不存在。
     */
    fun removeLegacyUbuntu(): Boolean {
        val dir = legacyUbuntuDir() ?: return true
        val shell = busybox()?.takeIf { it.isFile && health().ok }?.absolutePath
        val argv = if (shell != null) {
            listOf(shell, "sh", "-c", "chmod -R u+rwX \"\$1\" 2>/dev/null; rm -rf \"\$1\"", "sh", dir.absolutePath)
        } else {
            listOf("/system/bin/sh", "-c", "chmod -R u+rwX \"\$1\" 2>/dev/null; rm -rf \"\$1\"", "sh", dir.absolutePath)
        }
        runCatching {
            val process = ProcessBuilder(argv).redirectErrorStream(true).start()
            runCatching { process.outputStream.close() }
            process.inputStream.bufferedReader().readText()
            if (!process.waitFor(10, TimeUnit.MINUTES)) process.destroyForcibly()
        }.onFailure { AppLog.w(Tag, "清理旧 Ubuntu 环境失败", it) }
        if (dir.exists()) runCatching { dir.deleteRecursively() }
        return !dir.exists()
    }

    /** 目录占用。不跟随符号链接 —— rootfs 里有指回 `/` 的链接，跟进去会数到整个系统。 */
    internal fun sizeOf(dir: File): Long {
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                val link = runCatching { java.nio.file.Files.isSymbolicLink(child.toPath()) }.getOrDefault(false)
                when {
                    link -> Unit
                    child.isDirectory -> stack.addLast(child)
                    else -> total += child.length()
                }
            }
        }
        return total
    }
}
