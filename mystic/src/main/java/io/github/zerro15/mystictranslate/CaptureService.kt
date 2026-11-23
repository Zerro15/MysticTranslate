package io.github.zerro15.mystictranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings as AndroidSettings
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
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
            val surface = ProjectionFrames(this, projection, { stopSelf() }, {
                overlay?.clear()
                overlay?.updateGeometry()
            })
            frames = surface
            val dimensions = screenBounds()
            surface.start(dimensions.width(), dimensions.height())
            val windows = Overlay(this) { stopSelf() }
            overlay = windows
            windows.attach()
            val pipeline = Engines()
            engines = pipeline
            Diagnostics.start(dimensions.width(), dimensions.height())
            scope.launch { Diagnostics.refreshModels() }
            status.value = CaptureStatus(true, "Захват включён. Откройте игру")
            scope.launch {
                combine(Settings.current, Settings.activityVisible, windows.editMode, ::Triple).collectLatest { (options, visible, editing) ->
                    windows.setReadingScreen(!visible)
                    if (!visible && !editing) runLoop(surface, windows, pipeline, options)
                    else Diagnostics.status(if (editing) "Редактирование блоков" else "Пауза в настройках")
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            endMessage = "Не удалось запустить захват. Откройте приложение и разрешите захват снова"
            Diagnostics.failure("Запуск", endMessage, error)
            stopSelf()
        }
    }

    private fun promote(notification: Notification) = ServiceCompat.startForeground(
        this, 1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
    )

    private suspend fun runLoop(
        surface: ProjectionFrames, windows: Overlay, pipeline: Engines, options: Options,
    ) {
        windows.clear()
        val terms = if (options.profile) parseTerms(options.glossary) else emptyList()
        val analyzer = FrameAnalyzer(pipeline, options)
        var displayed: Dialogue? = null
        var missing = 0
        var failures = 0
        var mismatch = 0
        displayStatus(windows, "Жду кадр")
        while (currentCoroutineContext().isActive) {
            if (!AndroidSettings.canDrawOverlays(this)) {
                endMessage = "Разрешение на наложение отозвано"
                Diagnostics.failure("Наложение", endMessage)
                stopSelf()
                return
            }
            try {
                val generation = surface.generation
                val masked = windows.hasBlocks
                if (masked) {
                    surface.discardQueued()
                    windows.hideForCapture(true)
                }
                val bitmap = try {
                    surface.capture(masked)
                } finally {
                    if (masked) windows.hideForCapture(false)
                }
                if (bitmap == null) {
                    missing++
                    if (missing == 2 && Diagnostics.state.value.frameCount == 0L) {
                        Diagnostics.failure("Кадры", "Нет кадров. Выберите весь экран")
                        displayStatus(windows, "Нет кадров · весь экран")
                    } else if (missing == 3) {
                        displayStatus(windows, "Жду обновления кадра")
                    }
                } else {
                    missing = 0
                    val width = bitmap.width
                    val height = bitmap.height
                    Diagnostics.frame(width, height)
                    val target = screenBounds()
                    val renderedAspect = width.toFloat() / height
                    val screenAspect = target.width().toFloat() / target.height()
                    val result = try {
                        if (kotlin.math.abs(renderedAspect / screenAspect - 1f) > 0.12f) {
                            mismatch++
                            if (mismatch == 2) {
                                windows.clear()
                                Diagnostics.failure("Область", "Размер захвата не совпадает с экраном. Выберите весь экран")
                                displayStatus(windows, "Выберите весь экран")
                            }
                            null
                        } else {
                            mismatch = 0
                            analyzer.read(bitmap, generation)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    if (generation == surface.generation && result != null) {
                        Diagnostics.recognition(result.blocks, result.ocrMs)
                        if (result.blocks.isEmpty()) {
                            displayed = null
                            windows.clear()
                            val message = if (result.uniform) "Пустой кадр · проверьте захват" else "Китайский текст не найден"
                            displayStatus(windows, message)
                        } else {
                            val basis = PixelBox(target.left, target.top, target.right, target.bottom)
                            val context = Settings.dialogues.value
                            val rendered = ArrayList<OverlayBlock>(result.blocks.size)
                            var errorSeen = false
                            var passage: Dialogue? = null
                            for (block in result.blocks) {
                                val source = block.text
                                val start = android.os.SystemClock.elapsedRealtime()
                                val russian = try {
                                    Settings.corrections.value[source]
                                        ?: displayed?.takeIf { it.source == source }?.russian
                                        ?: pipeline.translate(source, options, terms, context.dropWhile { it.source == source })
                                } catch (error: EngineFailure) {
                                    if (!errorSeen) describeFailure(windows, error)
                                    errorSeen = true
                                    if (error.stage == Stage.OFFLINE && Diagnostics.state.value.models == ModelState.MISSING) break
                                    continue
                                }
                                if (generation != surface.generation) break
                                val rect = block.bounds
                                val placement = sourceBounds(PixelBox(rect.left, rect.top, rect.right, rect.bottom), basis, width, height)
                                    ?: continue
                                rendered += OverlayBlock(russian, Rect(placement.left, placement.top, placement.right, placement.bottom))
                                if (rendered.size == 1 || rendered.size % 8 == 0) {
                                    windows.setBlocks(rendered.toList(), options.font, options.opacity)
                                }
                                passage = Dialogue(source, russian)
                                Diagnostics.translated(source, russian, android.os.SystemClock.elapsedRealtime() - start)
                            }
                            if (generation == surface.generation) {
                                windows.setBlocks(rendered, options.font, options.opacity)
                                if (options.dialogue && passage != null && displayed != passage) {
                                    Settings.record(this, passage)
                                    displayed = passage
                                }
                                if (errorSeen) {
                                    failures++
                                    if (failures <= 2) analyzer.reset()
                                    delay(if (options.cloud && options.dialogue) 8_000L else 2_200L)
                                } else {
                                    failures = 0
                                    displayStatus(windows, "Перевод · ${rendered.size}")
                                }
                            }
                        }
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: EngineFailure) {
                describeFailure(windows, error)
                delay(2200)
            } catch (error: RuntimeException) {
                endMessage = "Захват прерван. Запустите его снова"
                Diagnostics.failure("Захват", endMessage, error)
                stopSelf()
                return
            }
            delay((options.interval * 1000).toLong())
        }
    }

    private fun describeFailure(windows: Overlay, error: EngineFailure) {
        val message = when (error.stage) {
            Stage.OCR -> "Ошибка OCR · проверьте движок"
            Stage.OFFLINE -> if (Diagnostics.state.value.models == ModelState.MISSING) {
                "Загрузите офлайн-модели"
            } else "Ошибка локального перевода"
            Stage.CLOUD -> "Ошибка API · проверьте настройки"
        }
        Diagnostics.failure(error.stage.name, message, error)
        displayStatus(windows, message)
    }

    private fun displayStatus(windows: Overlay, message: String) {
        Diagnostics.status(message)
        status.value = CaptureStatus(true, message)
        windows.showStatus(message)
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
        overlay?.updateGeometry()
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
        runCatching { overlay?.close() }.onFailure { Diagnostics.failure("Наложение", "Не удалось закрыть оверлей", it) }
        runCatching { engines?.close() }.onFailure { Diagnostics.failure("OCR", "Не удалось закрыть движок", it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        status.value = CaptureStatus(false, endMessage)
        Diagnostics.status(endMessage, active = false)
        super.onDestroy()
    }

    companion object {
        internal const val EXTRA_DATA = "projection"
        internal const val ACTION_STOP = "io.github.zerro15.mystictranslate.STOP"
        private const val CHANNEL = "capture"
        internal val status = MutableStateFlow(CaptureStatus())
    }
}
