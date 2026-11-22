package io.github.zerro15.mystictranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings as AndroidSettings
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class CaptureStatus(
    val active: Boolean = false, val message: String = "Захват выключен", val starting: Boolean = false,
)

class CaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var frames: ProjectionFrames? = null
    private var overlay: Overlay? = null
    private var engines: Engines? = null
    private var endMessage = "Захват остановлен"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (frames != null || status.value.starting) return START_NOT_STICKY
        status.value = CaptureStatus(message = "Запускаю захват", starting = true)
        scope.launch { startCapture(intent) }
        return START_NOT_STICKY
    }

    private suspend fun startCapture(intent: Intent?) {
        try {
            val activeNotification = notification()
            try {
                promote(activeNotification)
            } catch (error: SecurityException) {
                if (Build.VERSION.SDK_INT < 34) throw error
                // В исходном проекте этот повтор покрывал задержку системного AppOp на HyperOS.
                delay(200)
                promote(activeNotification)
            }
            val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
            if (data == null || !AndroidSettings.canDrawOverlays(this)) {
                endMessage = "Нужно снова разрешить наложение и захват экрана"
                stopSelf()
                return
            }
            Settings.initialize(this)
            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            val projection = checkNotNull(projectionManager.getMediaProjection(android.app.Activity.RESULT_OK, data))
            val surface = ProjectionFrames(this, projection, { stopSelf() }, { overlay?.clear() })
            frames = surface
            val dimensions = screenBounds()
            surface.start(dimensions.width(), dimensions.height())
            val windows = Overlay(this) { stopSelf() }
            overlay = windows
            windows.attach()
            val pipeline = Engines()
            engines = pipeline
            status.value = CaptureStatus(true, "Захват включён. Откройте игру")
            scope.launch {
                combine(Settings.current, Settings.activityVisible, windows.editMode, ::Triple).collectLatest { (options, visible, editing) ->
                    windows.setReadingScreen(!visible)
                    if (!visible && !editing) runLoop(surface, windows, pipeline, options)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            endMessage = "Не удалось запустить захват. Откройте приложение и разрешите захват снова"
            stopSelf()
        }
    }

    private fun promote(notification: Notification) = ServiceCompat.startForeground(
        this, 1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
    )

    private suspend fun runLoop(
        surface: ProjectionFrames, windows: Overlay, pipeline: Engines, options: Options
    ) {
        windows.clear()
        val terms = if (options.profile) parseTerms(options.glossary) else emptyList()
        var candidate: String? = null
        var displayed: Dialogue? = null
        var previous: Pair<Int, List<Recognized>>? = null
        while (currentCoroutineContext().isActive) {
            if (!AndroidSettings.canDrawOverlays(this)) {
                endMessage = "Разрешение на наложение отозвано"
                stopSelf()
                return
            }
            try {
                val generation = surface.generation
                val masked = windows.hasBlocks
                if (masked) windows.hideForCapture(true)
                val bitmap = try {
                    surface.capture(masked)
                } finally {
                    if (masked) windows.hideForCapture(false)
                }
                val recognized = if (bitmap != null) recognizeFrame(bitmap, pipeline, options) else null
                if (recognized != null) previous = generation to recognized
                // Статичный VirtualDisplay может не выдать второй кадр при пустом оверлее.
                val blocks = recognized ?: previous?.takeIf {
                    options.dialogue && !masked && it.first == generation
                }?.second
                if (blocks != null) {
                    if (generation == surface.generation) {
                        val text = if (options.dialogue) normalized(blocks.joinToString(" ") { it.text }) else ""
                        if (options.dialogue && text != candidate) {
                            candidate = text
                            windows.clear()
                        } else {
                            val history = Settings.dialogues.value.dropWhile { it.source == text }
                            val rendered = blocks.map { block ->
                                val source = normalized(block.text)
                                val prior = displayed?.takeIf { it.source == source }
                                val russian = prior?.russian ?: pipeline.translate(source, options, terms, history)
                                OverlayBlock(russian, block.bounds)
                            }
                            if (generation == surface.generation) {
                                windows.setBlocks(rendered, options.font, options.opacity)
                                if (options.dialogue && text.isNotBlank() && text != displayed?.source && rendered.isNotEmpty()) {
                                    val dialogue = Dialogue(text, rendered.single().text)
                                    Settings.record(this, dialogue)
                                    displayed = dialogue
                                } else if (text.isBlank()) {
                                    displayed = null
                                }
                            }
                        }
                    }
                    status.value = CaptureStatus(true,
                        if (blocks.isEmpty()) "Ищу китайские надписи" else "Захват и перевод работают")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: EngineFailure) {
                windows.clear()
                val message = when (error.stage) {
                    Stage.OCR -> "Ошибка OCR. Проверьте выбранный движок"
                    Stage.OFFLINE -> "Ошибка перевода. Загрузите офлайн-модели в MysticTranslate"
                    Stage.CLOUD -> "Облачный перевод недоступен. Проверьте адрес, модель и ключ"
                }
                status.value = CaptureStatus(true, message)
                delay(if (error.stage == Stage.CLOUD) 8_000 else 2_000)
            } catch (_: RuntimeException) {
                endMessage = "Захват прерван. Запустите его снова из MysticTranslate"
                stopSelf()
                return
            }
            delay((options.interval * 1000).toLong())
        }
    }

    private suspend fun recognizeFrame(bitmap: Bitmap, pipeline: Engines, options: Options): List<Recognized> {
        val top = if (options.dialogue) (bitmap.height * 0.58f).roundToInt() else 0
        val crop = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bitmap.height - top)
        val limit = if (options.detailed) 2560 else 1440
        val factor = (limit.toFloat() / maxOf(crop.width, crop.height)).coerceAtMost(1f)
        val scaled = if (factor == 1f) crop else Bitmap.createScaledBitmap(
            crop, (crop.width * factor).roundToInt().coerceAtLeast(1),
            (crop.height * factor).roundToInt().coerceAtLeast(1), true,
        )
        if (crop !== bitmap && crop !== scaled) crop.recycle()
        if (bitmap !== scaled) bitmap.recycle()
        // ML Kit может дочитать bitmap после отмены ожидания; в этом случае память освободит GC.
        val recognized = pipeline.recognize(scaled, options)
        val blocks = recognized.filter { han.containsMatchIn(it.text) && !it.bounds.isEmpty }
            .map { block ->
                val bounds = block.bounds
                Recognized(block.text, Rect(
                    (bounds.left / factor).roundToInt(), (bounds.top / factor).roundToInt() + top,
                    (bounds.right / factor).roundToInt(), (bounds.bottom / factor).roundToInt() + top,
                ))
            }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left })).take(50)
        scaled.recycle()
        if (!options.dialogue || blocks.isEmpty()) return blocks
        val bounds = Rect(blocks.first().bounds)
        blocks.drop(1).forEach { bounds.union(it.bounds) }
        return listOf(Recognized(blocks.joinToString(" ") { it.text }, bounds))
    }

    @Suppress("DEPRECATION")
    private fun screenBounds(): Rect {
        val wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) return wm.maximumWindowMetrics.bounds
        val metrics = android.util.DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT < 34) {
            val bounds = screenBounds()
            frames?.resize(bounds.width(), bounds.height())
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "Перевод поверх игры", NotificationManager.IMPORTANCE_LOW,
        ))
        val stop = PendingIntent.getService(this, 2, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_mystic)
            .setContentTitle("MysticTranslate").setContentText("Захват экрана включён")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build()).build()
    }

    override fun onDestroy() {
        scope.cancel()
        frames?.close()
        runCatching { overlay?.close() }
        runCatching { engines?.close() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        status.value = CaptureStatus(false, endMessage)
        super.onDestroy()
    }

    companion object {
        internal const val EXTRA_DATA = "projection"
        internal const val ACTION_STOP = "io.github.zerro15.mystictranslate.STOP"
        private const val CHANNEL = "capture"
        internal val status = MutableStateFlow(CaptureStatus())
    }
}
