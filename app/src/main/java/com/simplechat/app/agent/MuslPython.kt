package com.simplechat.app.agent

import android.os.StatFs
import android.os.SystemClock
import android.system.Os
import com.simplechat.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 按需下载的 CPython（musl 构建）。
 *
 * ### 从哪来
 *
 * astral-sh 的 python-build-standalone：可重定位的 CPython，解开即用、自带 pip。
 * 取它的 **musl** 构建 —— 解释器只依赖 `libc.so`，正好由 APK 里的 `libmusl.so`
 * （musl 的加载器兼 libc）顶上，不需要任何发行版的根文件系统。
 *
 * ### 怎么跑起来
 *
 * 解压出来的文件在应用私有目录里，安卓不许 `execve` 它们。但**把它们 mmap 进来
 * 执行是允许的**，所以实际的调用链是：
 *
 * ```
 * bin/python3 ──软链──▶ nativeLibraryDir/libdshaexec.so        （可执行）
 *                         └─ exec libmusl.so --library-path <py>/lib -- <py>/bin/python3 …
 * ```
 *
 * `sys.executable` 报的是那条软链，所以 `subprocess.run([sys.executable, …])`、
 * `python -m venv`、pip 的构建隔离这些「再起一个自己」的用法都走得通。
 *
 * ### pip 能装什么
 *
 * 纯 Python 的包没有限制。带 C 扩展的包取决于它有没有发 `musllinux` 的 wheel
 * （numpy / pandas / pillow / lxml 这类主流包都有）。pip 认 musl 靠的是去执行
 * 解释器 ELF 里写的加载器路径（`/lib/ld-musl-*.so.1`），安卓上没有那个文件，
 * 于是它会把这里当成「未知的 Linux」、只肯装纯 Python 包。[SiteCustomize]
 * 在 pip 加载那段探测代码时把答案直接填上。
 *
 * 只有源码包、需要现场编译的扩展装不了：这里没有编译器。
 */
object MuslPython {

    private const val Tag = "MuslPython"

    /** 固定的发布标签与版本。改了就等于换一份基线，已装的要重装。 */
    const val Release = "20261124"
    const val Version = "3.12.16"

    /** ELF 头里 e_machine 的取值。 */
    private const val ELF_AARCH64 = 183
    private const val ELF_X86_64 = 62

    /** 每个架构对应的发行包。哈希钉死：下载走的是第三方反代，不能只信「下完了」。 */
    private class Build(val arch: String, val sha256: String, val bytes: Long)

    private val Builds = mapOf(
        ELF_AARCH64 to Build(
            arch = "aarch64",
            sha256 = "58c596296e8d5a60474e04ff2d9ae601d706860cde2e5a92f065c57d2ce90b2b",
            bytes = 28_578_726,
        ),
        ELF_X86_64 to Build(
            arch = "x86_64",
            sha256 = "da7afb8381ddd5c4356c9c09cd54fe2f79f868c181f04df94264e7965f472db7",
            bytes = 28_220_561,
        ),
    )

    /**
     * 下载前缀，**按可用性排序**。
     *
     * 前两个是国内能连通的 GitHub 反代 —— 直连 `github.com` 在移动网络下经常
     * 几十 KB/s，28 MB 要下到天荒地老。空串表示直连，放最后兜底。
     */
    private val Mirrors = listOf(
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "",
    )

    /**
     * 发行包里用不上的东西，解压时直接跳过（85 MB → 44 MB，也少建一千多条软链）：
     *
     * - `include/`、`lib/pkgconfig`、`*-config`：给编译 C 扩展用的，这里没有编译器；
     * - `share/`：man 手册与 terminfo，终端页是按行收发的，用不上 curses；
     * - tcl / tk / tkinter / idle / turtledemo：手机上没有显示服务器；
     * - `lib/libpython*.so`：给「把 Python 嵌进别的程序」用的。解释器本体已经
     *   静态带了一份，musllinux 的 wheel 也不链接它，白占 22 MB。
     */
    private val Unwanted = Regex(
        "^python/(?:include|share)/" +
            "|^python/lib/(?:pkgconfig|itcl[^/]*|thread[^/]*|tcl[^/]*|tk[^/]*)(?:/|$)" +
            "|^python/lib/lib(?:tcl|tk|python)[^/]*$" +
            "|^python/lib/python3\\.[0-9]+/(?:tkinter|idlelib|turtledemo|test)/" +
            "|^python/bin/(?:idle[^/]*|python[^/]*-config)$",
    )

    internal fun unwanted(entryName: String): Boolean = Unwanted.containsMatchIn(entryName)

    private const val MarkerName = ".dsha-python-ok"

    /** 解压完约 44 MB，加上下载的 28 MB 与装包的余量。不预检的话会是「解到一半 ENOSPC」。 */
    private const val RequiredBytes = 160L * 1024 * 1024

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun home(): File = File(MuslEnv.baseDir(), "python")

    /**
     * 已经装好了吗。判据是文件在不在，不起进程。
     *
     * **必须看标记，不能只看 `bin/python3`。** 标记是随整棵目录一起原子改名
     * 进来的，它在就说明这棵树是完整解完、验过的那一棵。
     */
    fun isInstalled(): Boolean = runCatching {
        File(home(), MarkerName).isFile && File(home(), "bin/python3").exists()
    }.getOrDefault(false)

    /** 装的是哪个版本；没装返回 null。 */
    fun installedVersion(): String? = runCatching {
        File(home(), MarkerName).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun installedBytes(): Long = runCatching { MuslEnv.sizeOf(home()) }.getOrDefault(0L)

    /**
     * 下载并装好。
     *
     * [onProgress] 的第一位是 0..1，第二位是给人看的一句话。
     */
    suspend fun install(onProgress: (Float, String) -> Unit = { _, _ -> }): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                MuslEnv.unsupportedReason()?.let { throw IllegalStateException(it) }
                val health = MuslEnv.health(refresh = true)
                if (!health.ok) {
                    throw IllegalStateException("内置工具箱在这台设备上跑不起来（${health.detail}），Python 依赖它。")
                }
                val loader = MuslEnv.loader()?.takeIf { it.isFile }
                    ?: throw IllegalStateException("安装包里缺少 libmusl.so，请重新安装 App。")
                val build = Builds[elfMachine(loader)]
                    ?: throw IllegalStateException("认不出这台设备是 arm64 还是 x86_64")

                val base = MuslEnv.baseDir().apply { mkdirs() }
                val free = runCatching { StatFs(base.absolutePath).availableBytes }.getOrDefault(0L)
                if (free in 1 until RequiredBytes) {
                    throw IllegalStateException(
                        "存储空间不足：装 Python 需要约 ${RequiredBytes / 1024 / 1024} MB，现在只剩 ${free / 1024 / 1024} MB。",
                    )
                }

                val archive = File(base, "python-$Version-${build.arch}.tar.gz")
                val stage = File(base, "python.installing")
                try {
                    download(archive, build, onProgress)

                    onProgress(0.62f, "解压 Python $Version…")
                    stage.deleteRecursively()
                    stage.mkdirs()
                    extract(archive, stage) { fraction ->
                        onProgress(0.62f + fraction * 0.28f, "解压 Python $Version…")
                    }
                    val tree = File(stage, "python")
                    verify(tree)
                    writeSiteCustomize(tree)
                    File(tree, MarkerName).writeText("$Version\n")

                    onProgress(0.93f, "建立命令入口…")
                    val target = home()
                    if (target.exists() && !target.deleteRecursively()) {
                        throw IOException("无法移除旧的 Python 目录")
                    }
                    if (!tree.renameTo(target)) throw IOException("无法把 Python 放到位")
                    MuslEnv.ensureLayout()
                } finally {
                    archive.delete()
                    stage.deleteRecursively()
                }

                /*
                 * 真跑一次再报成功。
                 *
                 * 「文件都在」不等于「跑得起来」：加载器被系统拦、内核页大小不合、
                 * 存储被挂成 noexec，都只有真跑才知道。跑不起来就别留一个
                 * 「显示已安装、一用就崩」的状态，把原始输出交给用户。
                 */
                onProgress(0.96f, "验证…")
                val check = Shell.run(
                    "python3 -c 'import ssl, sqlite3, ctypes, zlib; import sys; print(sys.version.split()[0])'",
                    cwd = MuslEnv.homeDir(),
                    timeoutSeconds = 60,
                )
                if (check.exitCode != 0 || check.stdout.trim() != Version) {
                    uninstall()
                    throw IOException(
                        buildString {
                            append("Python 装好了但跑不起来（exit=").append(check.exitCode)
                            Shell.signalHint(check.exitCode)?.let { append("，").append(it) }
                            append("）")
                            val detail = (check.stderr.trim() + "\n" + check.stdout.trim()).trim()
                            if (detail.isNotEmpty()) append("：\n").append(detail.take(600))
                        },
                    )
                }
                PyRuntime.invalidate()
                AppLog.i(Tag, "Python $Version 已就绪：${home()}")
                onProgress(1f, "Python $Version 已就绪")
            }.onFailure { AppLog.w(Tag, "安装 Python 失败", it) }
        }

    /** 卸载：删掉整棵目录即可，命令入口是指向 APK 的软链，留着无妨。 */
    fun uninstall() {
        runCatching { home().deleteRecursively() }
        PyRuntime.invalidate()
    }

    // ── 下载 ─────────────────────────────────────────────────

    private fun download(archive: File, build: Build, onProgress: (Float, String) -> Unit) {
        val file = "cpython-$Version%2B$Release-${build.arch}-unknown-linux-musl-install_only_stripped.tar.gz"
        val path = "https://github.com/astral-sh/python-build-standalone/releases/download/$Release/$file"
        val totalMb = build.bytes / 1024 / 1024
        var lastError: Exception? = null

        for (mirror in Mirrors) {
            val url = "$mirror$path"
            val part = File(archive.absolutePath + ".part")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val request = Request.Builder().url(url).header("User-Agent", "DSHA/MuslPython").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    var written = 0L
                    var lastReport = 0L
                    response.body.byteStream().use { input ->
                        FileOutputStream(part).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                written += read
                                // 反代回一个登录页或限速页时，别傻等它把几百 MB 的东西下完
                                if (written > build.bytes) throw IOException("比预期的发行包大，放弃")
                                val now = SystemClock.uptimeMillis()
                                if (now - lastReport > 200) {
                                    lastReport = now
                                    val fraction = written.toFloat() / build.bytes
                                    onProgress(
                                        fraction * 0.60f,
                                        "下载 Python 发行包 ${written / 1024 / 1024} / $totalMb MB",
                                    )
                                }
                            }
                        }
                    }
                    if (written != build.bytes) throw IOException("下载不完整（$written / ${build.bytes} 字节）")
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != build.sha256) throw IOException("校验不符，下载到的不是预期的发行包")
                if (archive.exists()) archive.delete()
                check(part.renameTo(archive)) { "无法重命名下载文件" }
                return
            } catch (error: Exception) {
                lastError = error
                runCatching { part.delete() }
                AppLog.w(Tag, "下载失败：$url", error)
            }
        }
        throw IOException("下载 Python 失败：${lastError?.message ?: "所有下载源都不可达"}。检查一下网络再试。")
    }

    // ── 解压 ─────────────────────────────────────────────────

    /**
     * 把 `.tar.gz` 解到 [target]。
     *
     * 路径一律先规范化再落盘：发行包来自网络，`../` 开头的条目不能被它写到
     * 目标目录外面去。符号链接原样保留（`bin/python3 → python3.12`），
     * 硬链接在应用目录里建不了，退回成复制。
     */
    private fun extract(archive: File, target: File, onProgress: (Float) -> Unit) {
        val base = target.canonicalFile
        val prefix = base.path + File.separator
        val totalBytes = archive.length().coerceAtLeast(1L)
        var lastReport = 0L

        FileInputStream(archive).use { raw ->
            val counted = CountingStream(BufferedInputStream(raw, 128 * 1024))
            TarArchiveInputStream(GzipCompressorInputStream(counted)).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    val name = entry.name.trimStart('/').trimEnd('/').removePrefix("./")
                    if (name.isEmpty() || unwanted(name)) continue
                    val destination = File(base, name)
                    val path = runCatching { destination.canonicalPath }.getOrDefault(destination.path)
                    if (!path.startsWith(prefix) && path != base.path) {
                        throw SecurityException("压缩包含非法路径：${entry.name}")
                    }

                    when {
                        entry.isDirectory -> destination.mkdirs()
                        entry.isSymbolicLink -> {
                            destination.parentFile?.mkdirs()
                            destination.delete()
                            val escapes = entry.linkName.startsWith("/") ||
                                !insideAfterResolve(destination, entry.linkName, prefix)
                            if (escapes) throw SecurityException("压缩包含越界的符号链接：${entry.name}")
                            Os.symlink(entry.linkName, destination.absolutePath)
                        }
                        entry.isLink -> {
                            val source = File(base, entry.linkName.trimStart('/'))
                            destination.parentFile?.mkdirs()
                            destination.delete()
                            if (source.isFile) source.copyTo(destination, overwrite = true)
                        }
                        else -> {
                            destination.parentFile?.mkdirs()
                            FileOutputStream(destination).use { output -> tar.copyTo(output, 64 * 1024) }
                            // 可执行位留着：不靠它执行，但 `ls -l`、`test -x` 看到的该是原样
                            if (entry.mode and 0b001_001_001 != 0) destination.setExecutable(true, false)
                        }
                    }

                    val now = SystemClock.uptimeMillis()
                    if (now - lastReport > 200) {
                        lastReport = now
                        onProgress((counted.count.toFloat() / totalBytes).coerceIn(0f, 1f))
                    }
                }
            }
        }
    }

    private fun insideAfterResolve(link: File, target: String, prefix: String): Boolean {
        val resolved = runCatching { File(link.parentFile, target).canonicalPath }.getOrNull() ?: return false
        return resolved.startsWith(prefix)
    }

    private class CountingStream(private val source: java.io.InputStream) : java.io.InputStream() {
        var count = 0L
            private set

        override fun read(): Int = source.read().also { if (it >= 0) count++ }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            source.read(buffer, offset, length).also { if (it > 0) count += it }

        override fun close() = source.close()
    }

    private fun verify(tree: File) {
        if (!File(tree, "bin/python3").exists()) {
            throw IOException("解压完了但没有 bin/python3，发行包可能不完整")
        }
        if (stdlibDir(tree) == null) throw IOException("解压完了但没有标准库目录")
    }

    private fun stdlibDir(tree: File): File? =
        File(tree, "lib").listFiles()?.firstOrNull { it.isDirectory && it.name.startsWith("python3.") }

    private fun writeSiteCustomize(tree: File) {
        val site = File(stdlibDir(tree) ?: return, "site-packages").apply { mkdirs() }
        File(site, "sitecustomize.py").writeText(SiteCustomize)
    }

    /**
     * 让 pip 认出这是 musl 环境（原因见类注释「pip 能装什么」）。
     *
     * 做法是在 pip 导入它那份 `packaging._musllinux` 时，把「探测 musl 版本」的
     * 函数换成直接返回 1.2。导入钩子只盯这一个模块名，别的导入完全不经过它；
     * pip 以后改了内部结构，钩子就只是不再生效（退回到只装纯 Python 包），不会报错。
     */
    internal val SiteCustomize = """
        # DSHA: let pip recognise this interpreter as musllinux (see MuslPython.kt).
        import sys


        class _DshaMusllinuxHook:
            _names = ("pip._vendor.packaging._musllinux", "packaging._musllinux")

            def find_spec(self, name, path=None, target=None):
                if name not in self._names:
                    return None
                sys.meta_path.remove(self)
                try:
                    import importlib.util
                    spec = importlib.util.find_spec(name)
                except Exception:
                    spec = None
                finally:
                    sys.meta_path.insert(0, self)
                if spec is None or spec.loader is None:
                    return None
                loader = spec.loader
                original = loader.exec_module

                def exec_module(module):
                    original(module)
                    try:
                        version = module._MuslVersion(1, 2)
                        module._get_musl_version = lambda executable: version
                    except Exception:
                        pass

                loader.exec_module = exec_module
                return spec


        sys.meta_path.insert(0, _DshaMusllinuxHook())
    """.trimIndent() + "\n"

    /** ELF 头里的 e_machine（偏移 18，小端 16 位）。读不出来返回 -1。 */
    internal fun elfMachine(file: File): Int = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(20)
            if (input.read(header) != header.size) return@use -1
            if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte()) return@use -1
            (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
        }
    }.getOrDefault(-1)
}
