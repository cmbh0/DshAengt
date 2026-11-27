package com.simplechat.app.agent

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `file_ops` 的四个动作。
 *
 * 这个工具是**把 mkdir / mv / cp / rm 从 shell 手里拿回来**的那一步，
 * 所以两头都要测：
 * - 正常路径要真的动到磁盘（回执说"已移动"，文件就得在新位置）；
 * - 危险路径要**拒绝**（目标已存在、目录没给 recursive、路径越界）——
 *   它的价值有一半在于"不让模型顺手覆盖掉一个同名文件"。
 */
class FileOpsTest {

    private val tool = BuiltinTools.all(OkHttpClient()).first { it.name == "file_ops" }

    private fun root(): File {
        val dir = Files.createTempDirectory("fileops").toFile()
        Workspace.restore(dir.absolutePath, WorkspaceKind.DEVICE, "test")
        return dir
    }

    private suspend fun run(root: File, json: JsonObject): ToolOutcome =
        tool.execute(json, ToolContext(workspace = root, conversationId = "t"))

    private fun args(op: String, path: String, to: String? = null, recursive: Boolean? = null) =
        buildJsonObject {
            put("op", op)
            put("path", path)
            to?.let { put("to", it) }
            recursive?.let { put("recursive", it) }
        }

    private fun outputOf(outcome: ToolOutcome): String {
        assertTrue("期望成功，实际：$outcome", outcome is ToolOutcome.Ok)
        return (outcome as ToolOutcome.Ok).output
    }

    @Test
    fun `mkdir 建出多级目录`() = runBlocking {
        val root = root()
        val text = outputOf(run(root, args("mkdir", "a/b/c")))
        assertTrue(text.contains("已创建"))
        assertTrue(File(root, "a/b/c").isDirectory)
    }

    @Test
    fun `mkdir 遇到已存在的目录直接回报`() = runBlocking {
        val root = root()
        File(root, "x").mkdirs()
        assertTrue(outputOf(run(root, args("mkdir", "x"))).contains("已存在"))
    }

    @Test
    fun `move 改名后旧路径消失`() = runBlocking {
        val root = root()
        File(root, "old.md").writeText("hello")
        outputOf(run(root, args("move", "old.md", to = "new.md")))
        assertFalse(File(root, "old.md").exists())
        assertEquals("hello", File(root, "new.md").readText())
    }

    @Test
    fun `move 到已存在的目录等于搬进去`() = runBlocking {
        val root = root()
        File(root, "note.md").writeText("x")
        File(root, "docs").mkdirs()
        outputOf(run(root, args("move", "note.md", to = "docs")))
        assertTrue(File(root, "docs/note.md").isFile)
        assertFalse(File(root, "note.md").exists())
    }

    @Test
    fun `copy 保留源文件`() = runBlocking {
        val root = root()
        File(root, "a.txt").writeText("data")
        outputOf(run(root, args("copy", "a.txt", to = "b.txt")))
        assertTrue(File(root, "a.txt").isFile)
        assertEquals("data", File(root, "b.txt").readText())
    }

    @Test
    fun `目标已存在时拒绝覆盖`() = runBlocking {
        val root = root()
        File(root, "a.txt").writeText("A")
        File(root, "b.txt").writeText("B")
        val outcome = run(root, args("copy", "a.txt", to = "b.txt"))
        assertTrue(outcome is ToolOutcome.Failed)
        // 关键：失败不能留下半截改动
        assertEquals("B", File(root, "b.txt").readText())
        assertEquals("A", File(root, "a.txt").readText())
    }

    @Test
    fun `copy 目录到自己的子目录会被拦下`() = runBlocking {
        val root = root()
        File(root, "src/inner").mkdirs()
        val outcome = run(root, args("copy", "src", to = "src/inner"))
        assertTrue(outcome is ToolOutcome.Failed)
    }

    @Test
    fun `delete 删文件`() = runBlocking {
        val root = root()
        File(root, "tmp.log").writeText("x")
        assertTrue(outputOf(run(root, args("delete", "tmp.log"))).contains("已删除"))
        assertFalse(File(root, "tmp.log").exists())
    }

    @Test
    fun `delete 非空目录必须显式 recursive`() = runBlocking {
        val root = root()
        File(root, "cache/inner").mkdirs()
        File(root, "cache/inner/f.txt").writeText("x")

        val refused = run(root, args("delete", "cache"))
        assertTrue(refused is ToolOutcome.Failed)
        assertTrue(File(root, "cache/inner/f.txt").isFile)

        outputOf(run(root, args("delete", "cache", recursive = true)))
        assertFalse(File(root, "cache").exists())
    }

    @Test
    fun `路径越界被拒绝`() = runBlocking {
        val root = root()
        val outcome = run(root, args("mkdir", "../escape"))
        assertTrue("越界不该成功：$outcome", outcome is ToolOutcome.Failed)
    }
}
