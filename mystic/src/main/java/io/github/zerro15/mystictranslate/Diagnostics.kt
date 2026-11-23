package io.github.zerro15.mystictranslate

import android.util.Log
import com.google.mlkit.common.MlKitException
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await

internal enum class ModelState(val label: String) {
    UNKNOWN("Проверяю"), MISSING("Нужно скачать"), READY("Готовы"), UNAVAILABLE("Проверка не удалась"),
}

internal data class DiagnosticSnapshot(
    val message: String = "Захват выключен",
    val active: Boolean = false,
    val frameCount: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val chineseBlocks: Int = 0,
    val lastSource: String = "",
    val lastTranslation: Dialogue? = null,
    val ocrMs: Long? = null,
    val translateMs: Long? = null,
    val models: ModelState = ModelState.UNKNOWN,
    val errors: List<String> = emptyList(),
) {
    // Тексты, устройство, настройки API и скриншоты в копию отчёта не входят.
    fun report(): String = buildString {
        appendLine("MysticTranslate · диагностика")
        appendLine("Захват — " + if (active) "включён" else "выключен")
        appendLine(message)
        appendLine("Кадры — $frameCount, ${width}×$height")
        appendLine("Китайские блоки — $chineseBlocks")
        appendLine("OCR — ${ocrMs ?: "—"} мс, перевод — ${translateMs ?: "—"} мс")
        appendLine("Офлайн-модели — ${models.label}")
        errors.forEach { appendLine(it) }
    }.trimEnd()
}

internal object Diagnostics {
    val state = MutableStateFlow(DiagnosticSnapshot())

    fun start(width: Int, height: Int) {
        state.update { DiagnosticSnapshot(active = true, width = width, height = height, models = it.models,
            errors = it.errors, message = "Откройте игру") }
    }

    fun frame(width: Int, height: Int) = state.update {
        it.copy(frameCount = it.frameCount + 1, width = width, height = height)
    }

    fun recognition(blocks: List<Recognized>, ms: Long) = state.update {
        it.copy(chineseBlocks = blocks.size, lastSource = if (blocks.isEmpty()) it.lastSource else
            blocks.joinToString("\n") { item -> item.text }.take(2000),
            ocrMs = ms)
    }

    fun translated(source: String, russian: String, ms: Long) = state.update {
        it.copy(lastTranslation = Dialogue(source, russian), translateMs = ms)
    }

    fun status(message: String, active: Boolean = state.value.active) = state.update {
        it.copy(message = message, active = active)
    }

    fun failure(stage: String, message: String, error: Throwable? = null) {
        val cause = (error as? EngineFailure)?.cause ?: error
        val kind = cause?.let { " [${it.javaClass.simpleName}]" }.orEmpty()
        val mlCode = (cause as? MlKitException)?.let { " ML Kit ${it.errorCode}" }.orEmpty()
        val entry = "$stage · $message$kind$mlCode"
        // Логируем тип, код и место ошибки без текста исключения, где могут быть данные ввода.
        val site = cause?.stackTrace?.firstOrNull()?.let { " (${it.className}:${it.lineNumber})" }.orEmpty()
        state.update {
            it.copy(message = message, errors = (listOf(entry) + it.errors.filterNot { old -> old == entry }).take(12))
        }
        Log.w("MysticTranslate", entry + site)
    }

    suspend fun refreshModels(): ModelState {
        val result = try {
            val manager = RemoteModelManager.getInstance()
            val zh = TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build()
            val ru = TranslateRemoteModel.Builder(TranslateLanguage.RUSSIAN).build()
            if (manager.isModelDownloaded(zh).await() && manager.isModelDownloaded(ru).await()) ModelState.READY
            else ModelState.MISSING
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            failure("Модели", "Не удалось проверить офлайн-модели", error)
            ModelState.UNAVAILABLE
        }
        state.update { it.copy(models = result) }
        return result
    }
}
