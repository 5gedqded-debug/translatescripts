package app.mkp.translator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ApiConfig(val provider: Provider, val endpoint: String, val apiKey: String, val model: String) {
    val ready: Boolean get() = endpoint.isNotBlank() && model.isNotBlank() &&
        (apiKey.isNotBlank() || provider == Provider.CUSTOM)
}

class HttpException(val code: Int, msg: String) : Exception("HTTP $code: $msg")
class ApiException(msg: String) : Exception(msg)

class Item(val id: Int, val speaker: String?, val text: String)

object Llm {
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()
    @Volatile private var aborted = false

    /** Immediately closes every in-flight request and makes new ones fail with a cancellation. */
    fun abortAll() {
        aborted = true
        active.forEach { runCatching { it.disconnect() } }
    }

    fun reset() { aborted = false }

    // ---------- HTTP ----------
    private fun post(url: String, headers: Map<String, String>, body: String): String {
        if (aborted) throw CancellationException("Stopped")
        val conn = URL(url).openConnection() as HttpURLConnection
        active.add(conn)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = 240_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpException(code, text.take(500))
            return text
        } finally {
            active.remove(conn)
            conn.disconnect()
        }
    }

    private suspend fun <T> retrying(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: HttpException) {
                if (aborted) throw CancellationException("Stopped")
                val retry = e.code == 429 || e.code >= 500
                if (!retry || attempt >= 4) throw e
            } catch (e: IOException) {
                if (aborted) throw CancellationException("Stopped")
                if (attempt >= 4) throw e
            }
            delay(2000L shl attempt)
            attempt++
        }
    }

    // ---------- Провайдеры ----------
    suspend fun complete(cfg: ApiConfig, system: String, user: String): String = withContext(Dispatchers.IO) {
        retrying { completeOnce(cfg, system, user) }
    }

    private fun completeOnce(cfg: ApiConfig, system: String, user: String): String {
        val ep = cfg.endpoint.trimEnd('/')
        return when (cfg.provider) {
            Provider.GEMINI -> {
                val url = if (ep.contains(":generateContent")) ep else "$ep/models/${cfg.model}:generateContent"
                val safety = JSONArray()
                listOf(
                    "HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH",
                    "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT"
                ).forEach { safety.put(JSONObject().put("category", it).put("threshold", "BLOCK_NONE")) }
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", user)))))
                    .put("generationConfig", JSONObject().put("temperature", 0.3).put("responseMimeType", "application/json"))
                    .put("safetySettings", safety)
                val resp = JSONObject(post(url, mapOf("x-goog-api-key" to cfg.apiKey), body.toString()))
                val cands = resp.optJSONArray("candidates")
                if (cands == null || cands.length() == 0) {
                    val why = resp.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                    throw ApiException("Gemini: пустой ответ $why")
                }
                val c0 = cands.getJSONObject(0)
                val parts = c0.optJSONObject("content")?.optJSONArray("parts")
                    ?: throw ApiException("Gemini: нет текста (${c0.optString("finishReason")})")
                buildString { for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text")) }
            }
            Provider.CLAUDE -> {
                val url = if (ep.endsWith("/messages")) ep else "$ep/messages"
                val body = JSONObject()
                    .put("model", cfg.model)
                    .put("max_tokens", 8192)
                    .put("temperature", 0.3)
                    .put("system", system)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
                val resp = JSONObject(post(url, mapOf("x-api-key" to cfg.apiKey, "anthropic-version" to "2023-06-01"), body.toString()))
                val content = resp.optJSONArray("content") ?: throw ApiException("Claude: пустой ответ")
                buildString { for (i in 0 until content.length()) append(content.getJSONObject(i).optString("text")) }
            }
            else -> { // OpenAI, Grok, свой OpenAI-совместимый
                val url = if (ep.endsWith("/chat/completions")) ep else "$ep/chat/completions"
                val body = JSONObject()
                    .put("model", cfg.model)
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)))
                val headers = if (cfg.apiKey.isNotBlank()) mapOf("Authorization" to "Bearer ${cfg.apiKey}") else emptyMap()
                val resp = JSONObject(post(url, headers, body.toString()))
                val ch = resp.optJSONArray("choices")
                if (ch == null || ch.length() == 0) throw ApiException("Пустой ответ API")
                ch.getJSONObject(0).optJSONObject("message")?.optString("content")
                    ?: throw ApiException("Нет текста в ответе API")
            }
        }
    }

    // ---------- Промпты ----------
    private fun srcName(s: String) = if (s == "Авто") "the source language (auto-detect, most likely Japanese)" else s

    private fun system(src: String, tgt: String, extra: String) = """
You are a professional translator of visual novels and game scripts. Translate from ${srcName(src)} to $tgt.
Input is a JSON object: {"glossary": {name: translation}, "context": [previous lines, for understanding only], "lines": [{"id": N, "speaker": "optional", "text": "..."}]}.
Rules:
- Translate ONLY the "text" of each item in "lines". "context" must NOT be translated or returned.
- Natural, fluent $tgt that keeps each character's voice, emotion and nuance. Mature content must be translated faithfully, without censoring or softening.
- Keep quote brackets, ellipses and punctuation style. Do not merge, split, add or drop lines. Never change "id".
- Use "glossary" for character names consistently.
- Output ONLY valid JSON, no markdown, no comments: {"translations":[{"id":N,"text":"..."}]} containing every id exactly once.
$extra
""".trim()

    suspend fun translate(
        cfg: ApiConfig, src: String, tgt: String, extra: String,
        items: List<Item>, context: List<String>, glossary: Map<String, String>
    ): Map<Int, String> {
        val lines = JSONArray()
        items.forEach {
            val o = JSONObject().put("id", it.id)
            if (it.speaker != null) o.put("speaker", it.speaker)
            lines.put(o.put("text", it.text))
        }
        val gl = JSONObject()
        items.forEach { i -> i.speaker?.let { s -> glossary[s]?.let { gl.put(s, it) } } }
        val user = JSONObject().put("glossary", gl).put("context", JSONArray(context)).put("lines", lines).toString()
        val raw = complete(cfg, system(src, tgt, extra), user)
        return parseTranslations(raw)
    }

    fun parseTranslations(raw: String): Map<Int, String> {
        val s = stripFence(raw)
        val arr: JSONArray = try {
            val a = s.indexOf('{'); val b = s.lastIndexOf('}')
            if (a < 0 || b <= a) throw JSONException("no object")
            JSONObject(s.substring(a, b + 1)).optJSONArray("translations") ?: throw JSONException("no key")
        } catch (e: JSONException) {
            val a = s.indexOf('['); val b = s.lastIndexOf(']')
            if (a < 0 || b <= a) throw ApiException("Модель вернула не JSON: ${s.take(200)}")
            try { JSONArray(s.substring(a, b + 1)) } catch (e2: JSONException) {
                throw ApiException("Не удалось разобрать ответ модели: ${s.take(200)}")
            }
        }
        val out = HashMap<Int, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").toIntOrNull() ?: continue
            val t = o.optString("text", "")
            if (t.isNotBlank()) out[id] = t.trim()
        }
        return out
    }

    private fun stripFence(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
        if (s.endsWith("```")) s = s.removeSuffix("```").trim()
        return s
    }

    suspend fun translateNames(cfg: ApiConfig, src: String, tgt: String, names: List<String>): Map<String, String> {
        val system = "Translate or transliterate these visual novel character names/labels from ${srcName(src)} to $tgt, " +
            "as they would appear in a localized release (given names as names, roles like 'teacher' translated). " +
            "Output ONLY JSON: {\"names\":{\"original\":\"translated\"}} with every original exactly once."
        val raw = stripFence(complete(cfg, system, JSONArray(names).toString()))
        val a = raw.indexOf('{'); val b = raw.lastIndexOf('}')
        if (a < 0 || b <= a) throw ApiException("Не удалось перевести имена")
        val o = try { JSONObject(raw.substring(a, b + 1)) } catch (e: JSONException) { throw ApiException("Не удалось разобрать имена") }
        val n = o.optJSONObject("names") ?: o
        val out = HashMap<String, String>()
        n.keys().forEach { k -> val v = n.optString(k); if (v.isNotBlank()) out[k] = v.trim() }
        return out
    }
}
