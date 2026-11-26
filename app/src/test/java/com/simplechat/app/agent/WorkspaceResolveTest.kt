package com.simplechat.app.agent

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 工作区路径解析。
 *
 * 保护的是「绑了本机文件夹，AI 却读不到」这个问题不再回来：模型会把系统提示词里
 * 写的工作区真实路径原样交给文件工具，那种写法必须落在同一个文件上。
 */
class WorkspaceResolveTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = java.nio.file.Files.createTempDirectory("dsha-ws").toFile().canonicalFile
        File(root, "src").mkdirs()
        File(root, "src/main.kt").writeText("fun main() {}\n")
        Workspace.restore(root.path, WorkspaceKind.DEVICE, "ws")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `相对路径相对工作区根`() {
        assertEquals(File(root, "src/main.kt"), Workspace.resolve("src/main.kt"))
        assertEquals(root, Workspace.resolve("."))
    }

    @Test
    fun `工作区内的绝对路径原样使用`() {
        val absolute = File(root, "src/main.kt").path
        assertEquals(File(root, "src/main.kt"), Workspace.resolve(absolute))
        // 新文件也一样：父目录在工作区里即可
        assertEquals(File(root, "src/new.kt"), Workspace.resolve(File(root, "src/new.kt").path))
        // 不能再被拼成 <根>/<根的绝对路径>/…
        assertFalse(File(root, root.path.trimStart('/')).exists())
    }

    @Test
    fun `把工作区当根的写法仍然可用`() {
        assertEquals(File(root, "src/main.kt"), Workspace.resolve("/src/main.kt"))
        assertEquals(File(root, "notes.md"), Workspace.resolve("/notes.md"))
    }

    @Test
    fun `工程里叫 data 的目录不会被当成设备路径`() {
        File(root, "data").mkdirs()
        assertEquals(File(root, "data/train.csv"), Workspace.resolve("/data/train.csv"))
        assertFalse(Workspace.looksLikeDevicePath("/data/train.csv"))
        assertTrue(Workspace.looksLikeDevicePath("/data/user/0/com.example/files/a"))
        assertTrue(Workspace.looksLikeDevicePath("/sdcard/Download/a.pdf"))
        assertTrue(Workspace.looksLikeDevicePath("/storage/emulated/0/DCIM"))
    }

    @Test
    fun `指向工作区之外的设备路径报越界`() {
        for (path in listOf("/sdcard/Download/a.pdf", "/storage/emulated/0/Other/x.txt", "/proc/self/maps")) {
            try {
                Workspace.resolve(path)
                fail("应该越界：$path")
            } catch (e: SecurityException) {
                assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("工作区之外"))
            }
        }
    }

    @Test
    fun `点点跳不出去`() {
        try {
            Workspace.resolve("../outside.txt")
            fail("应该越界")
        } catch (e: SecurityException) {
            assertNotNull(e.message)
        }
        try {
            Workspace.resolve(File(root, "../outside.txt").path)
            fail("应该越界")
        } catch (e: SecurityException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `目录没了要说出原因而不是悄悄解绑`() {
        assertNull(Workspace.accessProblem())
        val gone = File(root, "gone")
        assertFalse(Workspace.restore(gone.path, WorkspaceKind.DEVICE, "gone"))
        // 绑定还在
        assertEquals(WorkspaceKind.DEVICE, Workspace.kind())
        val problem = Workspace.accessProblem()
        assertNotNull(problem)
        assertTrue(problem!!, problem.contains("不存在或无法访问"))
        try {
            Workspace.resolve("a.txt")
            fail("应该抛出")
        } catch (e: IllegalStateException) {
            assertEquals(problem, e.message)
        }
    }
}
