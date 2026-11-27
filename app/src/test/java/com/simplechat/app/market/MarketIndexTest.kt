package com.simplechat.app.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 索引解析。
 *
 * 这些断言保护的是**别人写坏的索引**不会变成用户面前的乱码：id 带斜杠会写出
 * 工作区、两条同 id 会互相覆盖、没有名字的行点下去会装出个无名插件。
 */
class MarketIndexTest {

    @Test
    fun `正常索引解析出全部字段`() {
        val list = Market.parse(
            """
            {
              "schema": 1,
              "name": "测试源",
              "plugins": [
                {"id":"a","name":"甲","description":"说明","version":"1.0.0",
                 "author":"me","kind":"skill","url":"https://x/a.zip","tags":["t"]}
              ]
            }
            """.trimIndent(),
        )
        assertEquals(1, list.size)
        val p = list.first()
        assertEquals("甲", p.displayName)
        assertEquals("1.0.0", p.version)
        assertEquals(listOf("t"), p.tags)
        assertTrue(p.installable)
    }

    @Test
    fun `id 不合法的条目被丢掉`() {
        val list = Market.parse(
            """
            {"plugins":[
              {"id":"../escape","name":"越界","url":"https://x/a.zip"},
              {"id":"Has Space","name":"带空格"},
              {"id":"ok-1","name":"正常"}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("ok-1"), list.map { it.id })
    }

    @Test
    fun `同名 id 只留第一条`() {
        val list = Market.parse(
            """
            {"plugins":[
              {"id":"dup","name":"第一条"},
              {"id":"dup","name":"第二条"}
            ]}
            """.trimIndent(),
        )
        assertEquals(1, list.size)
        assertEquals("第一条", list.first().name)
    }

    @Test
    fun `没有名字的条目不算数`() {
        val list = Market.parse("""{"plugins":[{"id":"x"},{"id":"y","name":"有名字"}]}""")
        assertEquals(listOf("y"), list.map { it.id })
    }

    @Test
    fun `坏 JSON 返回空表而不是抛异常`() {
        assertTrue(Market.parse("{ not json").isEmpty())
    }

    @Test
    fun `非 https 或 mcp 条目不可一键安装`() {
        val http = MarketPlugin(id = "a", name = "a", url = "http://x/a.zip")
        val mcp = MarketPlugin(id = "b", name = "b", kind = "mcp", url = "https://x/b.zip")
        val ok = MarketPlugin(id = "c", name = "c", url = "https://x/c.zip")
        assertFalse(http.installable)
        assertFalse(mcp.installable)
        assertTrue(ok.installable)
    }
}
