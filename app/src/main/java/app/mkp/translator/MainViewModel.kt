package app.mkp.translator

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class Similar(
    val script: ScriptData,
    val index: Int,
    val score: Float,
    val translation: String?,
    val original: String
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val settings = AppSettings(app)

    val scripts = mutableStateListOf<ScriptData>()
    /** script -> (first body line index -> translation) */
    private val tr = HashMap<String, SnapshotStateMap<Int, String>>()
    val glossary = mutableStateMapOf<String, String>()
    /** Translation memory: normalized original text -> translation. Main thread only. */
    private val cache = HashMap<String, String>()

    var loading by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var status by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var current by mutableStateOf<ScriptData?>(null)
    /** Body line index to scroll to when a chapter is opened from the global list. */
    var jumpStart by mutableStateOf<Int?>(null)

    private var magic = "mkp\u0000v100".toByteArray(Charsets.ISO_8859_1)
    private var job: Job? = null
    private var saveJob: Job? = null
    private val fileLock = Any()
    private val trivial = Regex("^[\\p{P}\\p{S}\\s　]+$")

    private val dir get() = File(getApplication<Application>().filesDir, "project").also { it.mkdirs() }

    init { viewModelScope.launch { loadFromDisk() } }

    fun trMap(name: String) = tr.getOrPut(name) { mutableStateMapOf() }
    fun translationOf(s: ScriptData, seg: Seg): String? = tr[s.name]?.get(seg.start)
    fun done(s: ScriptData) = tr[s.name]?.size ?: 0
    fun speakerLabel(name: String?): String? = name?.let { glossary[it] ?: it }

    private fun setScripts(list: List<ScriptData>) {
        scripts.clear(); scripts.addAll(list)
        list.forEach { trMap(it.name) }
        rebuildCache()
    }

    private fun rebuildCache() {
        cache.clear()
        for (s in scripts) {
            val m = tr[s.name] ?: continue
            for (sg in s.segs) { val t = m[sg.start] ?: continue; cache.putIfAbsent(sg.key, t) }
        }
    }

    // ---------- Loading ----------
    private suspend fun loadFromDisk() {
        val h = File(dir, "script.mkh"); val d = File(dir, "script.mkp")
        if (!h.exists() || !d.exists()) return
        loading = true
        try {
            val (list, mg) = withContext(Dispatchers.Default) {
                val hb = h.readBytes()
                Mkp.parse(hb, d.readBytes()).map { ScriptData(it.first, it.second) } to hb.copyOf(8)
            }
            magic = mg
            withContext(Dispatchers.IO) { loadSaved() }
            setScripts(list)
        } catch (e: Exception) {
            error = "Не удалось загрузить проект: ${e.message}"
        } finally { loading = false }
    }

    fun importFiles(resolver: ContentResolver, uris: List<Uri>) {
        viewModelScope.launch {
            loading = true
            try {
                val res = withContext(Dispatchers.IO) {
                    var h: ByteArray? = null; var d: ByteArray? = null
                    for (u in uris) {
                        val b = resolver.openInputStream(u)?.use { it.readBytes() } ?: continue
                        if (Mkp.isHeader(b) && b.size < 4_000_000) h = b else d = b
                    }
                    val hb = h ?: throw IllegalArgumentException("Выберите сразу оба файла: .mkh и .mkp")
                    val db = d ?: throw IllegalArgumentException("Выберите сразу оба файла: .mkh и .mkp")
                    val parsed = Mkp.parse(hb, db) // validate before writing to disk
                    File(dir, "script.mkh").writeBytes(hb); File(dir, "script.mkp").writeBytes(db)
                    File(dir, "translations.json").delete()
                    hb to parsed.map { ScriptData(it.first, it.second) }
                }
                job?.cancel(); Llm.abortAll(); current = null
                tr.clear(); glossary.clear()
                magic = res.first.copyOf(8)
                setScripts(res.second)
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally { loading = false }
        }
    }

    // ---------- Persistence ----------
    private fun loadSaved() {
        val f = File(dir, "translations.json")
        if (!f.exists()) return
        try {
            val root = JSONObject(f.readText())
            val t = root.optJSONObject("tr")
            t?.keys()?.forEach { sn ->
                val o = t.getJSONObject(sn); val m = trMap(sn)
                o.keys().forEach { k -> m[k.toInt()] = o.getString(k) }
            }
            root.optJSONObject("glossary")?.let { g -> g.keys().forEach { k -> glossary[k] = g.getString(k) } }
        } catch (e: Exception) { error = "Ошибка чтения сохранённого перевода: ${e.message}" }
    }

    private fun writeFile(text: String) = synchronized(fileLock) {
        val tmp = File(dir, "translations.tmp"); tmp.writeText(text)
        tmp.renameTo(File(dir, "translations.json"))
    }

    private fun snapshotText(): String {
        val snapshot = JSONObject()
        val t = JSONObject()
        tr.forEach { (sn, m) -> if (m.isNotEmpty()) { val o = JSONObject(); m.forEach { (k, v) -> o.put(k.toString(), v) }; t.put(sn, o) } }
        snapshot.put("tr", t)
        val g = JSONObject(); glossary.forEach { (k, v) -> g.put(k, v) }
        snapshot.put("glossary", g)
        return snapshot.toString()
    }

    fun saveNow() {
        val text = snapshotText()
        viewModelScope.launch(Dispatchers.IO) { writeFile(text) }
    }

    fun scheduleSave() {
        if (saveJob?.isActive == true) return
        saveJob = viewModelScope.launch { delay(4000); saveNow() }
    }

    fun setTranslation(s: ScriptData, seg: Seg, text: String) {
        if (text.isBlank()) {
            trMap(s.name).remove(seg.start)
        } else {
            val t = text.trim()
            trMap(s.name)[seg.start] = t
            cache[seg.key] = t
        }
        scheduleSave()
    }

    fun setGlossary(name: String, value: String) {
        if (value.isBlank()) glossary.remove(name) else glossary[name] = value.trim()
        scheduleSave()
    }

    // ---------- Similar / duplicate lines ----------
    suspend fun findSimilar(s: ScriptData, seg: Seg): List<Similar> = withContext(Dispatchers.Default) {
        val key = seg.key
        val q = Tx.bigrams(key)
        val out = ArrayList<Similar>()
        for (sc in scripts.toList()) {
            val m = tr[sc.name]
            for ((i, sg) in sc.segs.withIndex()) {
                if (sc === s && sg === seg) continue
                val score = if (sg.key == key) {
                    1f
                } else {
                    val a = key.length; val b = sg.key.length
                    if (a < 4 || b < 4 || b * 10 < a * 6 || b * 6 > a * 10) continue
                    Tx.dice(q, sg.key)
                }
                if (score >= 0.6f) out.add(Similar(sc, i, score, m?.get(sg.start), sg.original))
            }
        }
        out.sortedWith(compareByDescending<Similar> { it.score }.thenByDescending { it.translation != null }).take(12)
    }

    // ---------- Translation ----------
    fun openScript(s: ScriptData, jump: Seg? = null, auto: Boolean = true) {
        current = s
        jumpStart = jump?.start
        if (auto && settings.autoTranslate && !busy && settings.config().ready && done(s) < s.segs.size) translate(listOf(s))
    }

    fun translateAll() = translate(scripts.toList())
    fun translateCurrent() { current?.let { translate(listOf(it)) } }

    /** Stops everything right away: in-flight HTTP requests are closed, all workers are cancelled. */
    fun cancel() {
        Llm.abortAll()
        job?.cancel()
    }

    private fun translate(targets: List<ScriptData>) {
        if (busy) return
        val cfg = settings.config()
        if (!cfg.ready) { error = "Заполните настройки API (ключ, модель, адрес)"; return }
        error = null
        Llm.reset()
        job = viewModelScope.launch {
            busy = true
            try {
                runTranslate(cfg, targets)
            } catch (e: CancellationException) {
                status = "Остановлено"
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                status = "Остановлено из-за ошибки"
            } finally {
                busy = false
                saveNow()
            }
        }
    }

    /** One unique phrase to translate and every place it has to be written to. */
    private class Entry(val key: String, val text: String, val speaker: String?) {
        val targets = ArrayList<Pair<ScriptData, Int>>()
    }

    private class Batch(val entries: List<Entry>)

    private suspend fun runTranslate(cfg: ApiConfig, targets: List<ScriptData>) {
        val src = settings.sourceLang; val tgt = settings.targetName; val extra = settings.extraPrompt
        val batchSize = settings.batchSize.coerceIn(1, 100)
        val parallel = settings.parallel.coerceIn(1, 8)
        val useCache = settings.useCache

        // 1. Plan: fill from the cache, merge identical phrases, send only unique ones to the AI.
        val unique = LinkedHashMap<String, Entry>()
        var fromCache = 0
        var uid = 0
        for (s in targets) {
            val m = trMap(s.name)
            s.segs.forEachIndexed { i, seg ->
                if (m.containsKey(seg.start)) return@forEachIndexed
                if (trivial.matches(seg.original)) { m[seg.start] = seg.original; return@forEachIndexed }
                if (useCache) {
                    val hit = cache[seg.key]
                    if (hit != null) { m[seg.start] = hit; fromCache++; return@forEachIndexed }
                }
                val k = if (useCache) seg.key else seg.key + "\u0000" + (uid++)
                val e = unique.getOrPut(k) { Entry(seg.key, seg.original, seg.speaker) }
                if (e.targets.isNotEmpty() && useCache) fromCache++
                e.targets.add(s to i)
            }
        }
        val entries = unique.values.toList()
        val total = entries.size
        if (total == 0) {
            status = if (fromCache > 0) "Готово (из кэша: $fromCache)" else "Нечего переводить"
            return
        }
        val batches = ArrayList<Batch>()
        var cur = ArrayList<Entry>(); var chars = 0
        for (e in entries) {
            cur.add(e); chars += e.text.length
            if (cur.size >= batchSize || chars > 5000) { batches.add(Batch(cur)); cur = ArrayList(); chars = 0 }
        }
        if (cur.isNotEmpty()) batches.add(Batch(cur))

        // 2. Character names.
        if (settings.translateNames) {
            val names = entries.mapNotNull { it.speaker }.distinct().filter { !glossary.containsKey(it) }
            if (names.isNotEmpty()) {
                status = "Перевод имён…"
                names.chunked(60).forEach { chunk ->
                    Llm.translateNames(cfg, src, tgt, chunk).forEach { (k, v) -> glossary[k] = v }
                }
            }
        }

        // 3. Lines, in parallel. The first failure stops every other worker immediately.
        var processed = 0
        val sem = Semaphore(parallel)
        coroutineScope {
            batches.map { b ->
                async {
                    sem.withPermit {
                        try {
                            translateBatch(cfg, src, tgt, extra, b)
                        } catch (e: Exception) {
                            if (e !is CancellationException) Llm.abortAll()
                            throw e
                        }
                        processed += b.entries.size
                        status = "Переведено $processed из $total" + if (fromCache > 0) " (кэш/повторы: $fromCache)" else ""
                        scheduleSave()
                    }
                }
            }.awaitAll()
        }
        status = "Готово" + if (fromCache > 0) " (кэш/повторы: $fromCache)" else ""
    }

    private suspend fun translateBatch(cfg: ApiConfig, src: String, tgt: String, extra: String, b: Batch) {
        val firstT = b.entries.first().targets.first()
        val firstSegs = firstT.first.segs
        val ctx = (maxOf(0, firstT.second - 3) until firstT.second).map { firstSegs[it].original }
        var pending = b.entries
        repeat(2) {
            if (pending.isEmpty()) return
            val items = pending.mapIndexed { n, e -> Item(n, e.speaker?.let { x -> glossary[x] ?: x }, e.text) }
            val res = Llm.translate(cfg, src, tgt, extra, items, ctx, glossary)
            val missing = ArrayList<Entry>()
            pending.forEachIndexed { n, e ->
                val t = res[n]
                if (t == null) {
                    missing.add(e)
                } else {
                    cache[e.key] = t
                    for ((sc, i) in e.targets) trMap(sc.name)[sc.segs[i].start] = t
                }
            }
            pending = missing
        }
    }

    fun retranslate(s: ScriptData, seg: Seg) {
        if (busy) return
        val cfg = settings.config()
        if (!cfg.ready) { error = "Заполните настройки API (ключ, модель, адрес)"; return }
        val idx = s.segs.indexOf(seg)
        Llm.reset()
        job = viewModelScope.launch {
            busy = true; status = "Перевод строки…"
            try {
                trMap(s.name).remove(seg.start)
                val e = Entry(seg.key, seg.original, seg.speaker)
                e.targets.add(s to idx)
                translateBatch(cfg, settings.sourceLang, settings.targetName, settings.extraPrompt, Batch(listOf(e)))
                status = ""
            } catch (e: CancellationException) {
                status = "Остановлено"
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally { busy = false; scheduleSave() }
        }
    }

    fun translateAllNames() {
        if (busy) return
        val cfg = settings.config()
        if (!cfg.ready) { error = "Заполните настройки API (ключ, модель, адрес)"; return }
        val names = scripts.flatMap { sc -> sc.segs.mapNotNull { it.speaker } }.distinct().filter { !glossary.containsKey(it) }
        if (names.isEmpty()) { message = "Все имена уже переведены"; return }
        Llm.reset()
        job = viewModelScope.launch {
            busy = true; status = "Перевод имён…"
            try {
                names.chunked(60).forEach { chunk ->
                    Llm.translateNames(cfg, settings.sourceLang, settings.targetName, chunk).forEach { (k, v) -> glossary[k] = v }
                }
                status = "Готово"
            } catch (e: CancellationException) {
                status = "Остановлено"
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally { busy = false; saveNow() }
        }
    }

    fun testApi() {
        val cfg = settings.config()
        if (!cfg.ready) { error = "Заполните ключ, модель и адрес"; return }
        Llm.reset()
        viewModelScope.launch {
            try {
                val r = Llm.complete(cfg, "Reply with the single word OK.", "ping")
                message = "API работает. Ответ: " + r.trim().take(40)
            } catch (e: CancellationException) {
            } catch (e: Exception) { error = "Ошибка API: " + (e.message ?: e.toString()) }
        }
    }

    // ---------- Importing existing translations ----------
    /** Accepts a translated .mkh + .mkp pair and/or a translation.txt exported by this app. */
    fun importTranslation(resolver: ContentResolver, uris: List<Uri>) {
        if (scripts.isEmpty()) { error = "Сначала откройте оригинальный проект"; return }
        if (busy) return
        viewModelScope.launch {
            loading = true
            try {
                val report = withContext(Dispatchers.Default) {
                    var h: ByteArray? = null; var d: ByteArray? = null; var txt: String? = null
                    for (u in uris) {
                        val b = resolver.openInputStream(u)?.use { it.readBytes() } ?: continue
                        val head = String(b, 0, minOf(b.size, 8), Charsets.UTF_8).removePrefix("﻿")
                        when {
                            Mkp.isHeader(b) && b.size < 4_000_000 -> h = b
                            head.startsWith("===") -> txt = String(b, Charsets.UTF_8).removePrefix("﻿")
                            else -> d = b
                        }
                    }
                    val hb = h; val db = d; val tx = txt
                    val parts = ArrayList<String>()
                    if (hb != null && db != null) parts.add(importArchive(Mkp.parse(hb, db)))
                    if (tx != null) parts.add(importTxt(tx))
                    if (parts.isEmpty()) throw IllegalArgumentException("Выберите переведённые .mkh + .mkp и/или translation.txt")
                    parts.joinToString("\n")
                }
                rebuildCache()
                saveNow()
                message = report
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally { loading = false }
        }
    }

    private fun voteNames(votes: HashMap<String, HashMap<String, Int>>) {
        for ((orig, m) in votes) {
            if (glossary.containsKey(orig)) continue
            val best = m.entries.maxByOrNull { it.value }?.key ?: continue
            glossary[orig] = best
        }
    }

    private fun importArchive(translated: List<Pair<String, List<String>>>): String {
        val byName = translated.associate { it.first to it.second }
        var added = 0; var skipped = 0
        val badScripts = ArrayList<String>()
        val votes = HashMap<String, HashMap<String, Int>>()
        for (s in scripts) {
            val lines = byName[s.name] ?: continue
            val ts = ScriptParser.parse(lines)
            if (ts.size != s.segs.size) { badScripts.add(s.name); continue }
            val m = trMap(s.name)
            for (i in s.segs.indices) {
                val a = s.segs[i]; val b = ts[i]
                val text = ScriptParser.joinTranslated(lines.subList(b.start, b.end))
                if (a.speaker != null && b.speaker != null && a.speaker != b.speaker) {
                    val v = votes.getOrPut(a.speaker) { HashMap() }
                    v[b.speaker] = (v[b.speaker] ?: 0) + 1
                }
                if (text.isBlank() || Tx.norm(text) == a.key) { skipped++; continue }
                if (!m.containsKey(a.start)) { m[a.start] = text; added++ }
            }
        }
        voteNames(votes)
        return "Архив: добавлено $added строк, пропущено (не переведены) $skipped" +
            if (badScripts.isNotEmpty()) ", глав с другой структурой: ${badScripts.size} (${badScripts.take(3).joinToString()}…)" else ""
    }

    private fun importTxt(text: String): String {
        val secs = LinkedHashMap<String, ArrayList<String>>()
        var curName: String? = null
        val head = Regex("^=== (.*) ===$")
        for (raw in text.split("\n")) {
            val l = raw.removeSuffix("\r")
            val m = head.matchEntire(l)
            if (m != null) { curName = m.groupValues[1]; secs[curName] = ArrayList(); continue }
            if (l.isNotEmpty()) curName?.let { secs[it]?.add(l) }
        }
        var added = 0
        val badScripts = ArrayList<String>()
        val votes = HashMap<String, HashMap<String, Int>>()
        for (s in scripts) {
            val ls = secs[s.name] ?: continue
            if (ls.isEmpty()) continue
            if (ls.size != s.segs.size) { badScripts.add(s.name); continue }
            val m = trMap(s.name)
            for (i in s.segs.indices) {
                val a = s.segs[i]
                var t = ls[i]
                if (a.speaker != null) {
                    val k = t.indexOf(": ")
                    if (k in 1..60) {
                        val v = votes.getOrPut(a.speaker) { HashMap() }
                        val nm = t.substring(0, k)
                        v[nm] = (v[nm] ?: 0) + 1
                        t = t.substring(k + 2)
                    }
                }
                if (t.isBlank() || Tx.norm(t) == a.key) continue
                if (!m.containsKey(a.start)) { m[a.start] = t.trim(); added++ }
            }
        }
        voteNames(votes)
        return "translation.txt: добавлено $added строк" +
            if (badScripts.isNotEmpty()) ", пропущено глав (число строк не совпало): ${badScripts.size}" else ""
    }

    // ---------- Export ----------
    fun export(treeUri: Uri) {
        viewModelScope.launch {
            busy = true; status = "Экспорт…"
            try {
                val resolver = getApplication<Application>().contentResolver
                val charset = Mkp.charsetFor(settings.exportEncoding)
                val wrap = settings.wrapWidth
                val useNames = settings.translateNames
                val files = withContext(Dispatchers.Default) {
                    val out = scripts.map { s -> s.name to buildLines(s, wrap, useNames) }
                    val (h, d) = Mkp.build(out, charset, magic)
                    val txt = buildString {
                        for (s in scripts) {
                            append("=== ").append(s.name).append(" ===\n")
                            val m = trMap(s.name)
                            for (seg in s.segs) {
                                val t = m[seg.start] ?: continue
                                if (seg.speaker != null) append(speakerLabel(seg.speaker)).append(": ")
                                append(t).append('\n')
                            }
                            append('\n')
                        }
                    }.toByteArray(Charsets.UTF_8)
                    listOf(Triple("script.mkh", h, "application/octet-stream"),
                        Triple("script.mkp", d, "application/octet-stream"),
                        Triple("translation.txt", txt, "text/plain"))
                }
                withContext(Dispatchers.IO) {
                    val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                    for ((name, bytes, mime) in files) {
                        val u = DocumentsContract.createDocument(resolver, parent, mime, name)
                            ?: throw IllegalStateException("Не удалось создать $name")
                        resolver.openOutputStream(u)?.use { it.write(bytes) }
                    }
                }
                message = "Экспортировано: script.mkh, script.mkp, translation.txt"
                status = ""
            } catch (e: Exception) {
                error = "Ошибка экспорта: ${e.message}"
            } finally { busy = false }
        }
    }

    private fun buildLines(s: ScriptData, wrap: Int, useNames: Boolean): List<String> {
        val m = trMap(s.name)
        val out = ArrayList<String>(s.lines.size)
        var p = 0
        for (seg in s.segs) {
            val t = m[seg.start] ?: continue
            val upTo = if (seg.nameLine >= 0) seg.nameLine else seg.start
            while (p < upTo) { out.add(s.lines[p]); p++ }
            if (seg.nameLine >= 0) {
                val n = seg.speaker!!
                out.add("【" + (if (useNames) (glossary[n] ?: n) else n) + "】")
                p = seg.start
            }
            if (seg.choice) out.add(t.replace("\n", " ").trim()) else out.addAll(ScriptParser.wrap(t, wrap))
            p = seg.end
        }
        while (p < s.lines.size) { out.add(s.lines[p]); p++ }
        return out
    }

    override fun onCleared() { val t = snapshotText(); Thread { writeFile(t) }.start() }
}
