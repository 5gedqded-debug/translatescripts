package app.mkp.translator

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

class AppSettings(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val map = mutableStateMapOf<String, String>().apply {
        sp.all.forEach { (k, v) -> if (v is String) put(k, v) }
    }

    private fun str(k: String, d: String) = map[k] ?: d
    private fun put(k: String, v: String) { map[k] = v; sp.edit().putString(k, v).apply() }

    var provider: Provider
        get() = runCatching { Provider.valueOf(str("provider", "GEMINI")) }.getOrDefault(Provider.GEMINI)
        set(v) = put("provider", v.name)

    fun endpoint(p: Provider) = str("ep_${p.name}", p.defaultEndpoint)
    fun setEndpoint(p: Provider, v: String) = put("ep_${p.name}", v)
    fun apiKey(p: Provider) = str("key_${p.name}", "")
    fun setApiKey(p: Provider, v: String) = put("key_${p.name}", v)
    fun model(p: Provider) = str("model_${p.name}", p.defaultModel)
    fun setModel(p: Provider, v: String) = put("model_${p.name}", v)

    var targetLang: String get() = str("target", "Русский"); set(v) = put("target", v)
    var customTarget: String get() = str("target_custom", ""); set(v) = put("target_custom", v)
    var sourceLang: String get() = str("source", "Японский"); set(v) = put("source", v)
    var extraPrompt: String get() = str("extra", ""); set(v) = put("extra", v)
    var exportEncoding: String get() = str("enc", "UTF-8"); set(v) = put("enc", v)
    var autoTranslate: Boolean get() = str("auto", "1") == "1"; set(v) = put("auto", if (v) "1" else "0")
    var showOriginal: Boolean get() = str("showorig", "0") == "1"; set(v) = put("showorig", if (v) "1" else "0")
    var translateNames: Boolean get() = str("tnames", "1") == "1"; set(v) = put("tnames", if (v) "1" else "0")
    var batchSize: Int get() = str("batch", "30").toIntOrNull() ?: 30; set(v) = put("batch", v.toString())
    var parallel: Int get() = str("par", "2").toIntOrNull() ?: 2; set(v) = put("par", v.toString())
    var wrapWidth: Int get() = str("wrap", "46").toIntOrNull() ?: 46; set(v) = put("wrap", v.toString())

    var fontSize: Int get() = str("fs", "16").toIntOrNull() ?: 16; set(v) = put("fs", v.toString())

    val targetName: String get() = customTarget.ifBlank { targetLang }

    fun config() = ApiConfig(provider, endpoint(provider).trim(), apiKey(provider).trim(), model(provider).trim())
}
