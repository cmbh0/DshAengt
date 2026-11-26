package com.simplechat.app.agent

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * 「把文件里的一段原文换成新文本」背后的那部分逻辑。
 *
 * 本地的 `edit_file` / `patch_file` 都走这里。抽出来有两个原因：一是两个工具的
 * 匹配规则必须一模一样；二是这些全是纯函数，可以不起模拟器直接测。
 *
 * ### 为什么不只做逐字符匹配
 *
 * 模型给的 `old` 是它**读文件时看到的样子**，而它看到的和磁盘上的字节有两处
 * 稳定的出入：
 *
 * - **行尾**。Windows 来的文件是 CRLF，`read_file` 给模型看的是按行切好的文本，
 *   它回写的 `old` 里只有 `\n`；
 * - **行尾空白**。编辑器留下的行尾空格模型几乎从不照抄。
 *
 * 这两种情况下逐字符匹配必然失败，模型的反应多半是原样再试一次，然后告诉用户
 * 「已经改好了」。所以精确匹配找不到时，再按「忽略行尾空白与换行风格」找一次：
 * 仍然要求**唯一**，仍然只替换命中的那一段，文件其余部分一个字节都不动。
 *
 * 缩进不在放宽之列 —— 缩进错了在 Python / YAML 里是语义错误，宁可让它失败。
 */
internal object TextEdits {

    /** 怎么找到的。非 [EXACT] 时会在回执里说一声，让模型知道它的 old 并不精确。 */
    enum class Match { EXACT, NORMALIZED }

    sealed interface Outcome {
        /** [count] 是替换了几处（`all = true` 时可能大于 1）。 */
        data class Replaced(val text: String, val count: Int, val match: Match) : Outcome

        /** 找不到。[hint] 尽量指出差在哪，没有线索时为空串。 */
        data class NotFound(val hint: String) : Outcome

        /** 出现了不止一次而调用方要求唯一。 */
        data class Ambiguous(val count: Int) : Outcome
    }

    /**
     * 在 [text] 里把 [old] 换成 [new]。
     *
     * @param all true 则替换所有出现处；false 要求 [old] 在文中唯一。
     */
    fun replace(text: String, old: String, new: String, all: Boolean): Outcome {
        require(old.isNotEmpty()) { "old 不能为空" }

        // ── 1. 逐字符 ──
        val first = text.indexOf(old)
        if (first >= 0) {
            if (all) {
                return Outcome.Replaced(text.replace(old, new), occurrences(text, old), Match.EXACT)
            }
            if (text.indexOf(old, first + 1) >= 0) return Outcome.Ambiguous(occurrences(text, old))
            return Outcome.Replaced(
                text.substring(0, first) + new + text.substring(first + old.length),
                1,
                Match.EXACT,
            )
        }

        // ── 2. 忽略行尾空白与换行风格 ──
        val haystack = normalize(text)
        val needle = normalize(old).text
        if (needle.isEmpty()) return Outcome.NotFound("")
        val hits = ArrayList<Int>()
        var from = 0
        while (true) {
            val at = haystack.text.indexOf(needle, from)
            if (at < 0) break
            hits += at
            from = at + needle.length
        }
        if (hits.isEmpty()) return Outcome.NotFound(hint(text, old))
        if (hits.size > 1 && !all) return Outcome.Ambiguous(hits.size)

        // 新文本跟着文件的换行风格走，否则会在 CRLF 文件里插进几行 LF
        val replacement = if (usesCrlf(text)) {
            new.replace("\r\n", "\n").replace("\n", "\r\n")
        } else {
            new
        }
        val out = StringBuilder(text.length + (replacement.length - old.length) * hits.size)
        var cursor = 0
        for (at in hits) {
            val start = haystack.origin[at]
            // 终点取「最后一个命中字符之后」：它后面被规范化掉的行尾空白和 \r 留在原处
            val end = haystack.origin[at + needle.length - 1] + 1
            out.append(text, cursor, start).append(replacement)
            cursor = end
        }
        out.append(text, cursor, text.length)
        return Outcome.Replaced(out.toString(), hits.size, Match.NORMALIZED)
    }

    private fun occurrences(text: String, piece: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = text.indexOf(piece, from)
            if (at < 0) return count
            count++
            from = at + piece.length
        }
    }

    /** 规范化后的文本，以及每个字符在原文里的下标。 */
    private class Normalized(val text: String, val origin: IntArray)

    /**
     * 去掉每行行尾的空格 / 制表符 / `\r`（文件末尾也算一个行尾）。
     *
     * 行内的空白、行首的缩进都原样保留。
     */
    private fun normalize(source: String): Normalized {
        val out = StringBuilder(source.length)
        val origin = IntArray(source.length)
        var size = 0
        var index = 0
        while (index < source.length) {
            val ch = source[index]
            if (ch == ' ' || ch == '\t' || ch == '\r') {
                // 往后看：这一串空白后面如果紧跟换行或文件结束，就整串丢掉
                var end = index
                while (end < source.length && (source[end] == ' ' || source[end] == '\t' || source[end] == '\r')) end++
                if (end == source.length || source[end] == '\n') {
                    index = end
                    continue
                }
                while (index < end) {
                    out.append(source[index]); origin[size++] = index; index++
                }
                continue
            }
            out.append(ch); origin[size++] = index; index++
        }
        return Normalized(out.toString(), origin.copyOf(size))
    }

    private fun usesCrlf(text: String): Boolean {
        var crlf = 0
        var lf = 0
        for (i in text.indices) {
            if (text[i] == '\n') {
                if (i > 0 && text[i - 1] == '\r') crlf++ else lf++
            }
        }
        return crlf > lf
    }

    /**
     * 找不到时给一条线索。
     *
     * 只回答一个问题：「原文的第一行在文件里有没有」。有，说明模型看的是对的文件、
     * 只是后面某一行对不上（多半是缩进或它凭印象补的内容）；没有，多半是文件已经
     * 被改过，或者它记错了文件。两种情况下一步都是重新 read_file，但读哪儿不一样。
     */
    private fun hint(text: String, old: String): String {
        val wanted = old.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val head = wanted.firstOrNull() ?: return ""
        val lines = text.lines()
        val at = lines.indexOfFirst { it.trim() == head }
        if (at < 0) {
            return "原文的第一行（${head.take(60)}）在文件里一处都找不到，文件可能已经变了。"
        }
        // 从第一行往下逐行对（空行不算），报出第一处不同
        var line = at + 1
        for (index in 1 until wanted.size) {
            while (line < lines.size && lines[line].isBlank()) line++
            if (line >= lines.size) {
                return "原文的第一行在文件第 ${at + 1} 行，但文件到第 ${lines.size} 行就结束了，后面的内容对不上。"
            }
            if (lines[line].trim() != wanted[index]) {
                return "原文的第一行在文件第 ${at + 1} 行，但第 ${line + 1} 行与你给的不同：" +
                    "文件里是「${lines[line].trim().take(80)}」，你给的是「${wanted[index].take(80)}」。"
            }
            line++
        }
        return "原文各行都能从文件第 ${at + 1} 行起找到，但缩进或行内空白对不上。"
    }

    // ── 读写 ─────────────────────────────────────────────────

    /**
     * 按 UTF-8 严格解码。
     *
     * `readText()` 遇到非法字节会悄悄换成 U+FFFD，再写回去就是把原文件里的那些
     * 字节永久弄丢 —— GBK 编码的中文文件被「改一行」之后整篇变问号，就是这么来的。
     * 这里解不出来就抛，由调用方告诉模型换个办法。
     */
    @Throws(IOException::class)
    fun readUtf8(file: File): String {
        val bytes = file.readBytes()
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw IOException("NOT_UTF8")
        }
    }

    /** [readUtf8] 抛出的「不是 UTF-8」对应的人话。 */
    fun notUtf8Message(relative: String): String =
        "$relative 不是 UTF-8 编码的文本（可能是 GBK，或者是二进制文件）。文件工具只能安全地改 UTF-8 文本，" +
            "硬改会把原内容弄坏。需要处理的话用 python 工具按原编码读写。"

    /**
     * 写入并回读校验。
     *
     * 写完立刻把文件读回来和要写的内容比一遍，对不上就抛。看起来多余，但
     * 「工具说写好了、文件其实没变」是用户最难自己发现的一类问题（共享存储被
     * 别的进程占着、空间满了只写进去一半…），多读一次的代价远小于它。
     */
    @Throws(IOException::class)
    fun writeVerified(file: File, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        file.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                throw IOException("无法创建目录 ${parent.path}")
            }
        }
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            runCatching { output.fd.sync() }
        }
        val written = runCatching { file.readBytes() }.getOrElse {
            throw IOException("写入后读不回来：${it.message}")
        }
        if (!written.contentEquals(bytes)) {
            throw IOException(
                "写入后校验不一致：应为 ${bytes.size} 字节，磁盘上是 ${written.size} 字节。文件可能被别的程序占用，或存储空间不足。",
            )
        }
    }
}
