package com.simplechat.app.agent

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内置运行环境里不依赖设备的那部分：环境变量的拼法、发行包的取舍、参数解析。 */
class MuslEnvTest {

    @Test
    fun `时区写成 POSIX 形式，符号是反的`() {
        assertEquals("<+08>-8", MuslEnv.posixTimeZone(8 * 3_600_000))
        assertEquals("<-05>5", MuslEnv.posixTimeZone(-5 * 3_600_000))
        assertEquals("<+0530>-5:30", MuslEnv.posixTimeZone(5 * 3_600_000 + 30 * 60_000))
        assertEquals("<-0330>3:30", MuslEnv.posixTimeZone(-(3 * 3_600_000 + 30 * 60_000)))
        assertEquals("UTC0", MuslEnv.posixTimeZone(0))
    }

    @Test
    fun `resolv conf 最多三个服务器，第三席留给公共 DNS`() {
        val many = MuslEnv.resolvConf(listOf("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4"))
        val servers = many.lines().filter { it.startsWith("nameserver ") }.map { it.removePrefix("nameserver ") }
        assertEquals(listOf("10.0.0.1", "10.0.0.2", "223.5.5.5"), servers)

        val none = MuslEnv.resolvConf(emptyList()).lines().filter { it.startsWith("nameserver ") }
        assertEquals(3, none.size)

        // 系统给的恰好就是兜底的那个：不重复写
        val same = MuslEnv.resolvConf(listOf("223.5.5.5")).lines().filter { it.startsWith("nameserver ") }
        assertEquals(same.size, same.distinct().size)
    }

    @Test
    fun `用户名照安卓的约定`() {
        assertEquals("u0_a234", MuslEnv.userName(10_234))
        assertEquals("u10_a57", MuslEnv.userName(1_010_057))
        assertEquals("u0_2000", MuslEnv.userName(2_000))
    }

    @Test
    fun `证书文件只留 PEM 块`() {
        val text = """
            -----BEGIN CERTIFICATE-----
            AAAA
            BBBB
            -----END CERTIFICATE-----
            Certificate:
                Data:
                    Version: 3 (0x2)
        """.trimIndent()
        assertEquals(
            "-----BEGIN CERTIFICATE-----\nAAAA\nBBBB\n-----END CERTIFICATE-----\n",
            MuslEnv.extractPem(text),
        )
        assertEquals("", MuslEnv.extractPem("no certificate here"))
    }

    @Test
    fun `发行包里只跳过用不上的部分`() {
        val skip = listOf(
            "python/include/python3.12/Python.h",
            "python/share/terminfo/x/xterm",
            "python/lib/libpython3.12.so.1.0",
            "python/lib/libpython3.so",
            "python/lib/libtcl9.0.so",
            "python/lib/tcl9.0/init.tcl",
            "python/lib/tk9.0/tk.tcl",
            "python/lib/itcl4.3.8/itcl.tcl",
            "python/lib/thread3.0.6/pkgIndex.tcl",
            "python/lib/pkgconfig/python3.pc",
            "python/lib/python3.12/tkinter/__init__.py",
            "python/lib/python3.12/idlelib/idle.py",
            "python/lib/python3.12/turtledemo/clock.py",
            "python/bin/idle3.12",
            "python/bin/python3.12-config",
        )
        val keep = listOf(
            "python/bin/python3.12",
            "python/bin/python3",
            "python/bin/pip3",
            "python/lib/python3.12/os.py",
            "python/lib/python3.12/lib-dynload/_sqlite3.cpython-312-aarch64-linux-musl.so",
            "python/lib/python3.12/site-packages/pip/__init__.py",
            "python/lib/python3.12/ensurepip/__init__.py",
            // 名字里带 test / tk 的普通模块不能被误伤
            "python/lib/python3.12/unittest/case.py",
            "python/lib/python3.12/doctest.py",
            "python/lib/python3.12/tokenize.py",
            "python/lib/python3.12/threading.py",
        )
        skip.forEach { assertTrue("应该跳过 $it", MuslPython.unwanted(it)) }
        keep.forEach { assertFalse("不该跳过 $it", MuslPython.unwanted(it)) }
    }

    @Test
    fun `从 ELF 头读出架构`() {
        val dir = java.nio.file.Files.createTempDirectory("dsha-elf").toFile()
        try {
            fun elf(machine: Int) = File(dir, "m$machine").apply {
                val header = ByteArray(64)
                header[0] = 0x7f; header[1] = 'E'.code.toByte(); header[2] = 'L'.code.toByte(); header[3] = 'F'.code.toByte()
                header[18] = (machine and 0xff).toByte(); header[19] = (machine shr 8).toByte()
                writeBytes(header)
            }
            assertEquals(183, MuslPython.elfMachine(elf(183)))
            assertEquals(62, MuslPython.elfMachine(elf(62)))
            assertEquals(-1, MuslPython.elfMachine(File(dir, "text").apply { writeText("#!/bin/sh\necho hi\necho more text\n") }))
            assertEquals(-1, MuslPython.elfMachine(File(dir, "missing")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `sitecustomize 是合法的顶格 Python`() {
        val lines = MuslPython.SiteCustomize.lines()
        assertTrue(lines.first().startsWith("#"))
        assertTrue(lines.any { it == "import sys" })
        assertTrue(lines.any { it.startsWith("class _DshaMusllinuxHook") })
        assertTrue(lines.any { it == "sys.meta_path.insert(0, _DshaMusllinuxHook())" })
    }

    @Test
    fun `pip 失败的三种常见原因各有一句下一步`() {
        assertTrue(RuntimeTools.hint("ERROR: Failed building wheel for foo").contains("编译器"))
        assertTrue(RuntimeTools.hint("ERROR: No matching distribution found for nope").contains("包名或版本"))
        assertTrue(RuntimeTools.hint("ReadTimeoutError: HTTPSConnectionPool").contains("网络"))
        assertEquals("", RuntimeTools.hint("Successfully installed requests"))
    }

    // ── 工具参数 ──

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `残缺的参数 JSON 解析为 null 而不是空对象`() {
        assertNull(parseToolArguments("""{"path":"a.txt","content":"abc""", json))
        assertNull(parseToolArguments("""{"path":"a.txt","content":"abc"}}garbage{""", json))
        assertNull(parseToolArguments("""["not", "an", "object"]""", json))
        assertNotNull(parseToolArguments("""{"path":"a.txt"}""", json))
        assertNotNull(parseToolArguments("  {\"path\":\"a.txt\"}\n", json))
        // 没有参数的工具：空串等价于空对象
        assertEquals(JsonObject(emptyMap()), parseToolArguments("", json))
    }

    @Test
    fun `text 原样取值，str 才把空白当缺失`() {
        val args = buildJsonObject {
            put("blank", "   ")
            put("newline", "\n")
            put("empty", "")
            put("literal", "null")
            put("number", 42)
            put("nothing", JsonNull)
            put("nested", buildJsonObject { put("a", 1) })
        }
        assertEquals("   ", args.text("blank"))
        assertEquals("\n", args.text("newline"))
        assertEquals("", args.text("empty"))
        assertEquals("null", args.text("literal"))
        assertEquals("42", args.text("number"))
        assertNull(args.text("nothing"))
        assertNull(args.text("nested"))
        assertNull(args.text("absent"))

        assertNull(args.str("blank"))
        assertNull(args.str("literal"))
        assertEquals(JsonPrimitive(42), args["number"])
    }
}
