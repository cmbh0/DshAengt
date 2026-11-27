package com.simplechat.app.market

import android.content.Context
import com.simplechat.app.agent.Workspace
import com.simplechat.app.skill.SkillStore
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 插件市场的一个条目。
 *
 * 这里的"插件"目前就指**技能包**（一个目录 + `SKILL.md`，可带 `scripts/`）。
 * MCP 服务器要不要进市场是另一个问题：它需要用户填 token、填路径，
 * "一键安装"落下去也跑不起来，所以索引里即使出现 `kind=mcp` 也只读不装。
 */
@Serializable
data class MarketPlugin(
    /** 目录名，也是安装后的唯一标识。必须匹配 `[a-z0-9][a-z0-9_-]{0,63}`。 */
    val id: String,
    val name: String = "",
    val description: String = "",
    val version: String = "",
    val author: String = "",
    /** `skill`（默认，可一键安装）/ `mcp`（只展示）。 */
    val kind: String = "skill",
    /** zip 直链。装的是"顶层就是 `<id>/SKILL.md`"的包，与 `SkillStore.installZip` 认的形态一致。 */
    val url: String = "",
    val homepage: String = "",
    val tags: List<String> = emptyList(),
) {
    val installable: Boolean get() = kind == "skill" && url.startsWith("https://")
    val displayName: String get() = name.ifBlank { id }
}

@Serializable
data class MarketIndex(
    val schema: Int = 1,
    val name: String = "",
    val plugins: List<MarketPlugin> = emptyList(),
)

/**
 * 插件市场的索引与安装。
 *
 * ### 为什么是一个 JSON 文件而不是一套 API
 *
 * 索引就是一串静态 JSON —— 放 GitHub Pages、放 Gitee raw、放自己的 nginx，
 * 谁都能维护一份。做成服务端接口的话，生态的第一步就变成"先给我一台服务器"，
 * 那是把参与门槛架在自己身上。
 *
 * 于是这里的协议只有三条约定：
 *
 * ```
 * GET  <index url>            →  { "schema": 1, "name": "...", "plugins": [...] }
 * GET  <plugin.url>           →  zip，顶层是 <id>/SKILL.md
 * 全部走 https，全部有大小上限
 * ```
 *
 * ### 失败时给缓存
 *
 * 拉不到索引时退回上次成功的那一份（`files/market/cache.json`）。
 * 市场页最差的体验是"打开一片空白 + 一个红色错误" —— 用户上次明明见过列表。
 */
object Market {

    /**
     * 默认索引。
     *
     * 指向我们自己维护的一份（`dsha-market` 仓库的 `index.json`）。
     * 走 jsDelivr 而不是 raw.githubusercontent：后者在国内网络下经常连不上，
     * 而市场的第一次打开决定用户还会不会打开第二次。
     *
     * 用户可以换成任何一份同格式的地址 —— 这一项在设置里可见、可改、可恢复默认。
     */
    const val DefaultSource = "https://cdn.jsdelivr.net/gh/cmbh0/dsha-market@main/index.json"

    private const val PrefsName = "market"
    private const val KeySource = "index_url"

    /** 索引文件上限。超过就是拿错地址了（比如拿到一个网页）。 */
    private const val MaxIndexBytes = 512 * 1024L

    /** 技能包上限。技能是文本 + 小脚本，8 MB 已经很宽松。 */
    private const val MaxPackageBytes = 8L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
    }

    /** 当前索引地址。用户没改过就是内置那一条。 */
    fun source(): String =
        prefs?.getString(KeySource, null)?.takeIf { it.startsWith("http") } ?: DefaultSource

    fun setSource(url: String?) {
        val clean = url?.trim().orEmpty()
        prefs?.edit()?.apply {
            if (clean.isEmpty() || clean == DefaultSource) remove(KeySource) else putString(KeySource, clean)
        }?.apply()
    }

    private fun cacheFile(): File = File(File(Workspace.appDir(), "market"), "cache.json")

    /**
     * 拉索引。
     *
     * 返回的 [Fetched.fromCache] 为 true 表示网络失败、这份是上次的缓存 ——
     * 界面要据此显示"离线"提示，而不是让用户以为看到的是最新的。
     */
    suspend fun fetch(url: String = source()): Result<Fetched> = withContext(Dispatchers.IO) {
        val remote = runCatching { download(url, MaxIndexBytes) }
        val body = remote.getOrNull()
        if (body == null) {
            val cached = runCatching { cacheFile().takeIf { it.isFile }?.readText() }.getOrNull()
            val parsed = cached?.let { parse(it) }.orEmpty()
            return@withContext if (parsed.isEmpty()) {
                Result.failure(remote.exceptionOrNull() ?: IllegalStateException("索引拉取失败"))
            } else {
                Result.success(Fetched(parsed, fromCache = true))
            }
        }
        val parsed = parse(String(body, Charsets.UTF_8))
        if (parsed.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("索引里没有可用的插件"))
        }
        runCatching {
            cacheFile().parentFile?.mkdirs()
            cacheFile().writeBytes(body)
        }
        Result.success(Fetched(parsed, fromCache = false))
    }

    data class Fetched(val plugins: List<MarketPlugin>, val fromCache: Boolean)

    /**
     * 索引文本 → 插件列表。
     *
     * `internal` 是为了单测：这一层要抵住的是"别人的索引写错了"——
     * 缺字段、id 带斜杠、同一份里两条同 id。让它们走到 UI 上，
     * 用户看到的就是点「安装」装出来一个不是他想要的东西。
     */
    internal fun parse(raw: String): List<MarketPlugin> = runCatching {
        json.decodeFromString(MarketIndex.serializer(), raw).plugins
            .filter { it.id.matches(ID_PATTERN) && it.name.isNotBlank() }
            .distinctBy { it.id }
    }.getOrDefault(emptyList())

    private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")

    private fun download(url: String, limit: Long): ByteArray {
        require(url.startsWith("https://")) { "只支持 https 地址" }
        val request = Request.Builder().url(url).header("User-Agent", "DSHA-Market/1").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val declared = response.body?.contentLength() ?: -1
            if (declared > limit) error("文件过大（${declared / 1024} KB）")
            val bytes = response.body?.bytes() ?: error("响应为空")
            if (bytes.size > limit) error("文件过大（${bytes.size / 1024} KB）")
            return bytes
        }
    }

    /**
     * 安装一个插件。
     *
     * 校验分两步：包解开后必须真的产出 `plugin.id` 这个目录 —— 索引说装 A，
     * 包里却是 B，那是索引写错了，不能默默装成 B 还回一句"成功"。
     */
    suspend fun install(plugin: MarketPlugin): Result<List<String>> {
        if (plugin.kind != "skill") {
            return Result.failure(IllegalArgumentException("这个条目不是技能包，暂不支持一键安装"))
        }
        if (!plugin.url.startsWith("https://")) {
            return Result.failure(IllegalArgumentException("这个条目没有可用的下载地址"))
        }
        val bytes = runCatching { withContext(Dispatchers.IO) { download(plugin.url, MaxPackageBytes) } }
            .getOrElse { return Result.failure(it) }
        val installed = SkillStore.installZip(bytes)
        val ids = installed.getOrElse { return Result.failure(it) }
        if (installed.isSuccess && plugin.id !in ids) {
            return Result.failure(
                IllegalStateException("包里没有 ${plugin.id}（装出来的目录是 ${ids.joinToString("、").ifEmpty { "空" }}）"),
            )
        }
        /*
         * 装完直接启用。
         *
         * 用户点的是「安装」，心里的意思是「我要用它」。此前装完还得再去技能页
         * 找一遍开关 —— 那一步没有人会告诉我们他卡在那儿，他只会觉得"装了没用"。
         */
        if (installed.isSuccess) {
            SkillStore.setEnabled(plugin.id, true)
        } else {
            SkillStore.refresh()
        }
        return Result.success(ids)
    }
}
