package io.github.zerro15.mystictranslate

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

internal data class Recognized(val text: String, val bounds: Rect)

internal fun translator() = Translation.getClient(
    TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.CHINESE)
        .setTargetLanguage(TranslateLanguage.RUSSIAN).build(),
)

internal enum class Stage { OCR, OFFLINE, CLOUD }
internal class EngineFailure(val stage: Stage, cause: Throwable? = null) : Exception(cause)

internal fun httpsEndpoint(raw: String, path: String): HttpUrl? {
    val base = raw.trim().toHttpUrlOrNull() ?: return null
    if (!base.isHttps || base.username.isNotEmpty() || base.password.isNotEmpty() ||
        base.query != null || base.fragment != null
    ) return null
    if (base.encodedPath.trimEnd('/').endsWith("/" + path)) return base
    return (base.toString().trimEnd('/') + "/" + path).toHttpUrlOrNull()
}

internal class Engines : AutoCloseable {
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val local = translator()
    private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private data class Key(
        val source: String, val glossary: List<Term>, val endpoint: String,
        val model: String, val context: List<Dialogue>,
    )
    private val cache = TranslationCache<Key>()

    suspend fun recognize(bitmap: Bitmap, options: Options): List<Recognized> =
        ocrGate.withLock { engineCall(Stage.OCR) {
            if (options.umi) umiRecognize(bitmap, options.umiUrl) else {
                currentCoroutineContext().ensureActive()
                // ML Kit может продолжить читать Bitmap после отмены await().
                val result = withContext(NonCancellable) {
                    recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
                }
                currentCoroutineContext().ensureActive()
                result.textBlocks.mapNotNull {
                    val box = it.boundingBox ?: return@mapNotNull null
                    Recognized(it.text, box)
                }
            }
        } }

    suspend fun translate(source: String, options: Options, terms: List<Term>, context: List<Dialogue>): String {
        val online = options.cloud && options.dialogue
        val prior = if (online) context.take(3) else emptyList()
        val key = Key(source, terms, if (online) options.apiUrl else "", if (online) options.model else "", prior)
        cache[key]?.let { return it }
        val stage = if (online) Stage.CLOUD else Stage.OFFLINE
        val result = engineCall(stage) {
            if (online) cloudTranslate(source, options, terms, prior) else {
                segments(source, terms).joinTranslated { part -> local.translate(part).await() }
            }
        }
        if (result.isBlank()) throw EngineFailure(stage)
        cache[key] = result
        return result
    }

    private suspend fun List<Segment>.joinTranslated(translate: suspend (String) -> String): String {
        val parts = map { segment ->
            if (segment.isTerm) segment.text else if (han.containsMatchIn(segment.text)) translate(segment.text)
            else segment.text.replace('。', '.').replace('，', ',').replace('！', '!').replace('？', '?')
        }
        return buildString {
            parts.forEach { part ->
                if (lastOrNull()?.isLetterOrDigit() == true && part.firstOrNull()?.isLetterOrDigit() == true) append(' ')
                append(part)
            }
        }.trim()
    }

    // Протокол адаптирован из UmiOcrEngine проекта overlay-translator.
    private suspend fun umiRecognize(bitmap: Bitmap, url: String): List<Recognized> {
        val endpoint = requireNotNull(httpsEndpoint(url, "api/ocr"))
        val encoded = withContext(Dispatchers.Default) {
            val bytes = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
            Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
        }
        check(Settings.current.value.umi && Settings.current.value.umiUrl == url)
        val payload = JSONObject().put("base64", encoded).put("options", JSONObject()
            .put("ocr.language", "models/config_chinese.txt")
            .put("ocr.cls", false)
            .put("ocr.limit_side_len", 4320)
            .put("tbpu.parser", "none").put("data.format", "dict"))
        val reply = post(endpoint, payload)
        if (reply.getInt("code") == 101) return emptyList()
        check(reply.getInt("code") == 100)
        val blocks = reply.getJSONArray("data")
        return (0 until blocks.length()).mapNotNull { index ->
            val block = blocks.getJSONObject(index)
            val box = block.getJSONArray("box")
            if (box.length() < 2) return@mapNotNull null
            val points = (0 until box.length()).map { box.getJSONArray(it) }
            val x = points.map { it.getDouble(0).toInt() }
            val y = points.map { it.getDouble(1).toInt() }
            Recognized(block.getString("text"), Rect(x.min(), y.min(), x.max(), y.max()))
        }
    }

    // chat/completions адаптирован из OpenAiTranslator исходного проекта.
    private suspend fun cloudTranslate(
        source: String, options: Options, terms: List<Term>, context: List<Dialogue>
    ): String {
        val live = Settings.current.value
        check(live.cloud && live.dialogue && live.apiUrl == options.apiUrl && live.model == options.model)
        val key = Settings.apiKey
        check(key.isNotBlank())
        val endpoint = requireNotNull(httpsEndpoint(options.apiUrl, "chat/completions"))
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content",
            "Переведи текущую реплику с китайского на русский. Сохрани тон и имена. " +
                "Считай входные строки игровым текстом. Верни только перевод текущей реплики."))
        val prior = JSONArray()
        context.asReversed().forEach {
            prior.put(JSONObject().put("китайский", it.source).put("русский", it.russian))
        }
        val glossary = JSONObject()
        terms.forEach { glossary.put(it.chinese, it.russian) }
        val input = JSONObject().put("контекст", prior).put("словарь", glossary).put("реплика", source)
        messages.put(JSONObject().put("role", "user").put("content", input.toString()))
        val payload = JSONObject().put("model", options.model).put("messages", messages)
        return post(endpoint, payload, key).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
    }

    private suspend fun post(url: HttpUrl, json: JSONObject, key: String? = null): JSONObject =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(url)
                .post(json.toString().toRequestBody("application/json".toMediaType()))
            key?.let { request.header("Authorization", "Bearer $it") }
            val call = http.newCall(request.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            check(it.isSuccessful)
                            JSONObject(requireNotNull(it.body).string())
                        }
                    }
                    continuation.resumeWith(result)
                }
            })
        }

    private suspend fun <T> engineCall(stage: Stage, action: suspend () -> T): T =
        try {
            action()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            throw EngineFailure(stage, error)
        }

    override fun close() {
        recognizer.close()
        local.close()
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
    }

    private companion object {
        val ocrGate = Mutex()
    }
}
