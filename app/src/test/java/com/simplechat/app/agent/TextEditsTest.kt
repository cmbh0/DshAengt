package com.simplechat.app.agent

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `edit_file` / `patch_file` 的匹配与落盘。
 *
 * 这些用例对应的都是真实出过的「AI 说改了、文件没变（或改坏了）」：
 * CRLF 文件、行尾空格、非 UTF-8 文件、同一段出现多次。
 */
class TextEditsTest {

    private fun replaced(text: String, old: String, new: String, all: Boolean = false): TextEdits.Outcome.Replaced =
        TextEdits.replace(text, old, new, all) as? TextEdits.Outcome.Replaced
            ?: error("期望替换成功，实际是 ${TextEdits.replace(text, old, new, all)}")

    @Test
    fun `逐字符命中时只换那一段`() {
        val result = replaced("a\nb\nc\n", "b\n", "B\n")
        assertEquals("a\nB\nc\n", result.text)
        assertEquals(TextEdits.Match.EXACT, result.match)
        assertEquals(1, result.count)
    }

    @Test
    fun `new 只有一个换行也照写不误`() {
        // 早先空白串会被当成没传，这种「把一行换成空行」的编辑等于把换行删了
        assertEquals("a\n\nc\n", replaced("a\nb\nc\n", "b", "").text)
        assertEquals("a\n\n\nc\n", replaced("a\nb\nc\n", "b", "\n").text)
    }

    @Test
    fun `CRLF 文件用 LF 的 old 也能命中，并保持 CRLF`() {
        val file = "line1\r\nline2\r\nline3\r\n"
        val result = replaced(file, "line1\nline2\n", "L1\nL2\n")
        assertEquals("L1\r\nL2\r\nline3\r\n", result.text)
        assertEquals(TextEdits.Match.NORMALIZED, result.match)
    }

    @Test
    fun `行尾空白不同也能命中，没碰到的行尾空白原样保留`() {
        val file = "def f():   \n    return 1\t\nprint(f())\n"
        val result = replaced(file, "def f():\n    return 1", "def f():\n    return 2")
        // 第二行原有的行尾制表符不在命中范围内，留着
        assertEquals("def f():\n    return 2\t\nprint(f())\n", result.text)
        assertEquals(TextEdits.Match.NORMALIZED, result.match)
    }

    @Test
    fun `缩进不同不放宽`() {
        val outcome = TextEdits.replace("if x:\n    y = 1\n", "if x:\n  y = 1\n", "z", all = false)
        assertTrue(outcome is TextEdits.Outcome.NotFound)
        val hint = (outcome as TextEdits.Outcome.NotFound).hint
        assertTrue("线索里应该指出第一行在哪：$hint", hint.contains("第 1 行"))
    }

    @Test
    fun `第一行都找不到时线索要说明`() {
        val outcome = TextEdits.replace("alpha\nbeta\n", "gamma\ndelta\n", "x", all = false)
        assertTrue((outcome as TextEdits.Outcome.NotFound).hint.contains("一处都找不到"))
    }

    @Test
    fun `后面的行对不上时线索指出是哪一行`() {
        val outcome = TextEdits.replace("a\nb\nc\n", "a\nb\nX\n", "x", all = false)
        val hint = (outcome as TextEdits.Outcome.NotFound).hint
        assertTrue(hint, hint.contains("第 3 行") && hint.contains("「c」"))
    }

    @Test
    fun `出现多次默认报歧义，all 才全部替换`() {
        val outcome = TextEdits.replace("x = 1\nx = 1\n", "x = 1", "x = 2", all = false)
        assertEquals(2, (outcome as TextEdits.Outcome.Ambiguous).count)

        val all = replaced("x = 1\nx = 1\n", "x = 1", "x = 2", all = true)
        assertEquals("x = 2\nx = 2\n", all.text)
        assertEquals(2, all.count)
    }

    @Test
    fun `规范化匹配下的歧义同样报出来`() {
        val outcome = TextEdits.replace("a  \nb\na\t\nb\n", "a\nb", "z", all = false)
        assertEquals(2, (outcome as TextEdits.Outcome.Ambiguous).count)
    }

    @Test
    fun `文件末尾没有换行也能命中最后一行`() {
        assertEquals("a\nZ", replaced("a\nb   ", "b", "Z").text.trimEnd())
    }

    @Test
    fun `写完回读一致`() {
        val dir = createTempDir()
        try {
            val file = File(dir, "sub/a.txt")
            TextEdits.writeVerified(file, "你好\nworld\n")
            assertEquals("你好\nworld\n", file.readText())
            // 覆盖成更短的内容：不能留下旧内容的尾巴
            TextEdits.writeVerified(file, "x")
            assertEquals("x", file.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `非 UTF-8 文件拒绝读取而不是悄悄弄坏`() {
        val dir = createTempDir()
        try {
            val file = File(dir, "gbk.txt")
            // "中文" 的 GBK 编码
            file.writeBytes(byteArrayOf(0xD6.toByte(), 0xD0.toByte(), 0xCE.toByte(), 0xC4.toByte()))
            try {
                TextEdits.readUtf8(file)
                fail("应该抛出")
            } catch (e: IOException) {
                assertEquals("NOT_UTF8", e.message)
            }
            val utf8 = File(dir, "utf8.txt").apply { writeText("中文") }
            assertEquals("中文", TextEdits.readUtf8(utf8))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun createTempDir(): File = java.nio.file.Files.createTempDirectory("dsha-edit").toFile()
}
