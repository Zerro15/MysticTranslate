package io.github.zerro15.mystictranslate

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal data class Options(
    val dialogue: Boolean = false,
    val interval: Float = 1.8f,
    val detailed: Boolean = false,
    val font: Float = 17f,
    val opacity: Float = 0.72f,
    val profile: Boolean = true,
    val glossary: String = "",
    val cloud: Boolean = false,
    val apiUrl: String = "",
    val model: String = "",
    val umi: Boolean = false,
    val umiUrl: String = "",
)

internal data class Dialogue(val source: String, val russian: String)

internal object Settings {
    private val options = MutableStateFlow(Options())
    private val history = MutableStateFlow<List<Dialogue>>(emptyList())
    val corrections = MutableStateFlow<Map<String, String>>(emptyMap())
    val current get() = options
    val dialogues get() = history
    val activityVisible = MutableStateFlow(false)

    // Ключ живёт только в памяти процесса; после его завершения облако нужно включить повторно.
    @Volatile var apiKey = ""
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        val prefs = context.getSharedPreferences("mystic", Context.MODE_PRIVATE)
        options.value = Options(
            dialogue = prefs.getBoolean("dialogue", false),
            interval = prefs.getFloat("interval", 1.8f),
            detailed = prefs.getBoolean("detailed", false),
            font = prefs.getFloat("font", 17f),
            opacity = prefs.getFloat("opacity", 0.72f),
            profile = prefs.getBoolean("profile", true),
            glossary = prefs.getString("glossary", null)
                ?: context.assets.open("lord-of-mysteries.txt").bufferedReader().use { it.readText() },
            apiUrl = prefs.getString("apiUrl", "").orEmpty(),
            model = prefs.getString("model", "").orEmpty(),
            umiUrl = prefs.getString("umiUrl", "").orEmpty(),
        )
        history.value = runCatching {
            val saved = JSONArray(prefs.getString("history", "[]"))
            (0 until saved.length()).map {
                val entry = saved.getJSONObject(it)
                Dialogue(entry.getString("source"), entry.getString("russian"))
            }
        }.getOrDefault(emptyList())
        corrections.value = runCatching {
            val saved = JSONObject(prefs.getString("corrections", "{}").orEmpty())
            saved.keys().asSequence().take(120).associateWith(saved::getString)
        }.onFailure {
            Diagnostics.failure("Исправления", "Не удалось прочитать исправления", it)
        }.getOrDefault(emptyMap())
        initialized = true
    }

    fun save(context: Context, value: Options) {
        options.value = value
        context.getSharedPreferences("mystic", Context.MODE_PRIVATE).edit()
            .putBoolean("dialogue", value.dialogue).putFloat("interval", value.interval)
            .putBoolean("detailed", value.detailed).putFloat("font", value.font)
            .putFloat("opacity", value.opacity).putBoolean("profile", value.profile)
            .putString("glossary", value.glossary).putString("apiUrl", value.apiUrl)
            .putString("model", value.model).putString("umiUrl", value.umiUrl)
            .apply()
    }

    fun record(context: Context, entry: Dialogue) {
        if (history.value.firstOrNull() != entry) setHistory(context, listOf(entry) + history.value)
    }

    fun clearHistory(context: Context) = setHistory(context, emptyList())

    fun correct(context: Context, source: String, russian: String) {
        val key = normalizeRecognition(source)
        require(key.isNotBlank() && russian.isNotBlank())
        val values = (listOf(key to russian.trim()) + corrections.value.toList().filterNot { it.first == key })
            .take(120).toMap()
        corrections.value = values
        context.getSharedPreferences("mystic", Context.MODE_PRIVATE).edit()
            .putString("corrections", JSONObject(values).toString()).apply()
        setHistory(context, history.value.map { if (it.source == key) it.copy(russian = values.getValue(key)) else it })
    }

    private fun setHistory(context: Context, entries: List<Dialogue>) {
        history.value = entries.take(40)
        val encoded = JSONArray()
        history.value.forEach { entry ->
            encoded.put(JSONObject().put("source", entry.source).put("russian", entry.russian))
        }
        context.getSharedPreferences("mystic", Context.MODE_PRIVATE).edit()
            .putString("history", encoded.toString()).apply()
    }
}
