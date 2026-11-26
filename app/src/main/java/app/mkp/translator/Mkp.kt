package app.mkp.translator

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Формат: .mkh — индекс (заголовок 32 байта + 115*32 байт записей, записи XOR 0xA7),
 * .mkp — данные скриптов (весь файл XOR 0xA7, смещения выровнены по 4 байта).
 * Внутри — текст Shift-JIS (CRLF).
 */
object Mkp {
    private const val KEY = 0xA7

    fun isHeader(b: ByteArray): Boolean =
        b.size >= 32 && b[0] == 'm'.code.toByte() && b[1] == 'k'.code.toByte() &&
            b[2] == 'p'.code.toByte() && b[3] == 0.toByte()

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun putBe32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }

    fun sjis(): Charset =
        runCatching { Charset.forName("windows-31j") }.getOrElse { Charset.forName("Shift_JIS") }

    fun charsetFor(name: String): Charset = if (name.startsWith("Shift")) sjis() else Charsets.UTF_8

    private fun decodeText(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        String(bytes, sjis())
    }

    fun splitLines(t: String): List<String> = t.split("\n").map { it.removeSuffix("\r") }

    /** Возвращает список (имя скрипта, строки). */
    fun parse(mkh: ByteArray, mkp: ByteArray): List<Pair<String, List<String>>> {
        val count = be32(mkh, 16)
        require(count in 1..100000 && mkh.size >= 32 + count * 32) { "Повреждённый .mkh" }
        val out = ArrayList<Pair<String, List<String>>>(count)
        for (i in 0 until count) {
            val base = 32 + i * 32
            val e = ByteArray(32) { (mkh[base + it].toInt() xor KEY).toByte() }
            val off = be32(e, 0)
            val size = be32(e, 4)
            val nl = (e[8].toInt() and 0xFF).coerceAtMost(23)
            val name = String(e, 9, nl, Charsets.UTF_8)
            require(off >= 0 && size >= 0 && off + size <= mkp.size) { "Файл .mkp не подходит к .mkh" }
            val raw = ByteArray(size) { (mkp[off + it].toInt() xor KEY).toByte() }
            out.add(name to splitLines(decodeText(raw)))
        }
        return out
    }

    /** Собирает пару (.mkh, .mkp). */
    fun build(
        scripts: List<Pair<String, List<String>>>,
        charset: Charset,
        magic: ByteArray
    ): Pair<ByteArray, ByteArray> {
        val bodies = scripts.map { (_, lines) -> lines.joinToString("\r\n").toByteArray(charset) }
        var cur = 0
        val offs = IntArray(bodies.size)
        for (i in bodies.indices) {
            cur = (cur + 3) and 3.inv()
            offs[i] = cur
            cur += bodies[i].size
        }
        val total = (cur + 3) and 3.inv()
        val data = ByteArray(total) { KEY.toByte() }
        for (i in bodies.indices) {
            val b = bodies[i]
            for (j in b.indices) data[offs[i] + j] = (b[j].toInt() xor KEY).toByte()
        }
        val n = scripts.size
        val hsize = 32 + 32 * n
        val h = ByteArray(hsize)
        System.arraycopy(magic, 0, h, 0, minOf(8, magic.size))
        putBe32(h, 8, hsize); putBe32(h, 12, total); putBe32(h, 16, n)
        for (i in 0 until n) {
            val e = ByteArray(32)
            putBe32(e, 0, offs[i]); putBe32(e, 4, bodies[i].size)
            val nb = scripts[i].first.toByteArray(Charsets.UTF_8)
            val len = minOf(nb.size, 23)
            e[8] = len.toByte()
            System.arraycopy(nb, 0, e, 9, len)
            for (k in 0 until 32) h[32 + i * 32 + k] = (e[k].toInt() xor KEY).toByte()
        }
        return h to data
    }
}
