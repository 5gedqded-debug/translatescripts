package app.mkp.translator

enum class Provider(val title: String, val defaultEndpoint: String, val defaultModel: String) {
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash"),
    CLAUDE("Anthropic Claude", "https://api.anthropic.com/v1", "claude-sonnet-5-5"),
    OPENAI("OpenAI ChatGPT", "https://api.openai.com/v1", "gpt-4o-mini"),
    GROK("xAI Grok", "https://api.x.ai/v1", "grok-3-mini"),
    CUSTOM("Свой (OpenAI-совместимый)", "http://192.168.1.10:1234/v1", "model-name")
}

val TARGET_LANGS = listOf(
    "Русский", "English", "Українська", "Español", "Français", "Deutsch", "Italiano",
    "Português", "Polski", "Türkçe", "中文 (简体)", "한국어", "Bahasa Indonesia", "Tiếng Việt"
)
val SOURCE_LANGS = listOf("Японский", "Английский", "Китайский", "Корейский", "Авто")

/** Одна «реплика»: диапазон строк тела [start, end), опциональная строка имени 【имя】. */
class Seg(
    val start: Int,
    val end: Int,
    val nameLine: Int,
    val speaker: String?,
    val choice: Boolean,
    val original: String
)

class ScriptData(val name: String, val lines: List<String>) {
    val segs: List<Seg> = ScriptParser.parse(lines)
}

object ScriptParser {
    private val nameRe = Regex("^【(.*)】$")

    private fun isCmd(t: String) = t.isEmpty() || t.startsWith("#") || t.startsWith(";") || t == "*"

    fun parse(lines: List<String>): List<Seg> {
        val segs = ArrayList<Seg>()
        var i = 0
        var selecting = false
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("#")) { selecting = l.startsWith("#select"); i++; continue }
            if (isCmd(l)) { i++; continue }
            if (selecting) { segs.add(Seg(i, i + 1, -1, null, true, l)); i++; continue }
            var nameLine = -1
            var speaker: String? = null
            var s = i
            val m = nameRe.matchEntire(l)
            if (m != null) { nameLine = i; speaker = m.groupValues[1]; s = i + 1 }
            var e = s
            while (e < lines.size && !isCmd(lines[e])) e++
            if (e > s) segs.add(Seg(s, e, nameLine, speaker, false, join(lines.subList(s, e))))
            i = maxOf(e, i + 1)
        }
        return segs
    }

    /** Склеивает строки, разорванные вручную для окна сообщений. */
    private fun join(ls: List<String>): String {
        val sb = StringBuilder(ls[0])
        for (k in 1 until ls.size) {
            val next = ls[k].trimStart('　', ' ')
            if (sb.isNotEmpty() && next.isNotEmpty() && sb.last().code < 128 && next.first().code < 128) sb.append(' ')
            sb.append(next)
        }
        return sb.toString()
    }

    /** Разбивает перевод на строки по ширине (0 — без переноса). */
    fun wrap(text: String, width: Int): List<String> {
        val t = text.replace("\r", "").replace("\n", " ").trim()
        if (width <= 0) return listOf(t)
        val w = maxOf(width, 10)
        if (t.length <= w) return listOf(t)
        val out = ArrayList<String>()
        var rest = t
        while (rest.length > w) {
            var cut = rest.lastIndexOf(' ', w)
            if (cut < w / 2) cut = w
            out.add(rest.substring(0, cut).trimEnd())
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }
}
