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

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val settings = AppSettings(app)

    val scripts = mutableStateListOf<ScriptData>()
    /** script -> (start строки -> перевод) */
    private val tr = HashMap<String, androidx.compose.runtime.snapshots.SnapshotStateMap<Int, String>>()
    val glossary = mutableStateMapOf<String, String>()

    var loading by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var status by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var current by mutableStateOf<ScriptData?>(null)

    private var magic = "mkp\u0000v100".toByteArray(Charsets.ISO_8859_1)
    private var job: Job? = null
    private var saveJob: Job? = null

    private val dir get() = File(getApplication<Application>().filesDir, "project").also { it.mkdirs() }

    init { viewModelScope.launch { loadFromDisk() } }

    fun trMap(name: String) = tr.getOrPut(name) { mutableStateMapOf() }
    fun translationOf(s: ScriptData, seg: Seg): String? = trMap(s.name)[seg.start]
    fun done(s: ScriptData) = trMap(s.name).size
    fun speakerLabel(name: String?): String? = name?.let { glossary[it] ?: it }

    // ---------- Загрузка ----------
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
            scripts.clear(); scripts.addAll(list)
        } catch (e: Exception) {
            error = "Не удалось загрузить проект: ${e.message}"
        } finally { loading = false }
    }

    fun importFiles(resolver: ContentResolver, uris: List<Uri>) {
        viewModelScope.launch {
            loading = true
            try {
                withContext(Dispatchers.IO) {
                    var h: ByteArray? = null; var d: ByteArray? = null
                    for (u in uris) {
                        val b = resolver.openInputStream(u)?.use { it.readBytes() } ?: continue
                        if (Mkp.isHeader(b) && b.size < 4_000_000) h = b else d = b
                    }
                    val hb = h ?: throw IllegalArgumentException("Выберите сразу оба файла: .mkh и .mkp")
                    val db = d ?: throw IllegalArgumentException("Выберите сразу оба файла: .mkh и .mkp")
                    val parsed = Mkp.parse(hb, db) // проверка до записи на диск
                    File(dir, "script.mkh").writeBytes(hb); File(dir, "script.mkp").writeBytes(db)
                    File(dir, "translations.json").delete()
                    withContext(Dispatchers.Main) {
                        job?.cancel(); current = null
                        tr.clear(); glossary.clear()
                        magic = hb.copyOf(8)
                        scripts.clear(); scripts.addAll(parsed.map { ScriptData(it.first, it.second) })
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally { loading = false }
        }
    }

    // ---------- Сохранение переводов ----------
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

    private val fileLock = Any()

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
        if (text.isBlank()) trMap(s.name).remove(seg.start) else trMap(s.name)[seg.start] = text.trim()
        scheduleSave()
    }

    // ---------- Перевод ----------
    private val trivial = Regex("^[\\p{P}\\p{S}\\s　]+$")

    fun openScript(s: ScriptData) {
        current = s
        if (settings.autoTranslate && !busy && settings.config().ready && done(s) < s.segs.size) translate(listOf(s))
    }

    fun translateAll() = translate(scripts.toList())
    fun translateCurrent() { current?.let { translate(listOf(it)) } }
    fun cancel() { job?.cancel() }

    private fun translate(targets: List<ScriptData>) {
        if (busy) return
        val cfg = settings.config()
        if (!cfg.ready) { error = "Заполните настройки API (ключ, модель, адрес)"; return }
        error = null
        job = viewModelScope.launch {
            busy = true
            try {
                runTranslate(cfg, targets)
                status = "Готово"
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

    private class Batch(val script: ScriptData, val segIdx: List<Int>)

    private suspend fun runTranslate(cfg: ApiConfig, targets: List<ScriptData>) {
        val src = settings.sourceLang; val tgt = settings.targetName; val extra = settings.extraPrompt
        val batchSize = settings.batchSize.coerceIn(1, 100)
        val parallel = settings.parallel.coerceIn(1, 8)

        // 1. что переводить
        val batches = ArrayList<Batch>()
        for (s in targets) {
            val m = trMap(s.name)
            var cur = ArrayList<Int>(); var chars = 0
            s.segs.forEachIndexed { i, seg ->
                if (m.containsKey(seg.start)) return@forEachIndexed
                if (trivial.matches(seg.original)) { m[seg.start] = seg.original; return@forEachIndexed }
                cur.add(i); chars += seg.original.length
                if (cur.size >= batchSize || chars > 5000) { batches.add(Batch(s, cur)); cur = ArrayList(); chars = 0 }
            }
            if (cur.isNotEmpty()) batches.add(Batch(s, cur))
        }
        val total = batches.sumOf { it.segIdx.size }
        if (total == 0) { status = "Нечего переводить"; return }

        // 2. имена
        if (settings.translateNames) {
            val names = batches.flatMap { b -> b.segIdx.mapNotNull { b.script.segs[it].speaker } }
                .distinct().filter { !glossary.containsKey(it) }
            if (names.isNotEmpty()) {
                status = "Перевод имён…"
                names.chunked(60).forEach { chunk ->
                    Llm.translateNames(cfg, src, tgt, chunk).forEach { (k, v) -> glossary[k] = v }
                }
            }
        }

        // 3. реплики
        var processed = 0
        val sem = Semaphore(parallel)
        coroutineScope {
            batches.map { b ->
                async {
                    sem.withPermit {
                        translateBatch(cfg, src, tgt, extra, b)
                        processed += b.segIdx.size
                        status = "Переведено $processed из $total"
                        scheduleSave()
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun translateBatch(cfg: ApiConfig, src: String, tgt: String, extra: String, b: Batch) {
        val s = b.script; val m = trMap(s.name)
        val first = b.segIdx.first()
        val ctx = (maxOf(0, first - 3) until first).map { s.segs[it].original }
        var pending = b.segIdx
        repeat(2) {
            if (pending.isEmpty()) return
            val items = pending.map { i -> Item(i, s.segs[i].speaker?.let { n -> glossary[n] ?: n }, s.segs[i].original) }
            val res = Llm.translate(cfg, src, tgt, extra, items, ctx, glossary)
            val missing = ArrayList<Int>()
            for (i in pending) {
                val t = res[i]
                if (t != null) m[s.segs[i].start] = t else missing.add(i)
            }
            pending = missing
        }
    }

    fun retranslate(s: ScriptData, seg: Seg) {
        if (busy) return
        val cfg = settings.config()
        if (!cfg.ready) { error = "Заполните настройки API (ключ, модель, адрес)"; return }
        val idx = s.segs.indexOf(seg)
        job = viewModelScope.launch {
            busy = true; status = "Перевод строки…"
            try {
                trMap(s.name).remove(seg.start)
                translateBatch(cfg, settings.sourceLang, settings.targetName, settings.extraPrompt, Batch(s, listOf(idx)))
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
        viewModelScope.launch {
            try {
                val r = Llm.complete(cfg, "Reply with the single word OK.", "ping")
                message = "API работает. Ответ: " + r.trim().take(40)
            } catch (e: Exception) { error = "Ошибка API: " + (e.message ?: e.toString()) }
        }
    }

    // ---------- Экспорт ----------
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
