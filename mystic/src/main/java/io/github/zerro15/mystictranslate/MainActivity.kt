package io.github.zerro15.mystictranslate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {
    private var feedback by mutableStateOf("")
    private var loadingModel by mutableStateOf(false)
    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val grant = result.data
        if (result.resultCode != RESULT_OK || grant == null) {
            feedback = "Захват экрана не разрешён"
        } else if (!AndroidSettings.canDrawOverlays(this)) {
            feedback = "Сначала разрешите наложение поверх приложений"
        } else {
            try {
                val start = Intent(this, CaptureService::class.java).putExtra(CaptureService.EXTRA_DATA, grant)
                ContextCompat.startForegroundService(this, start)
                feedback = ""
            } catch (_: RuntimeException) {
                feedback = "Не удалось запустить захват. Повторите разрешение из приложения"
            }
        }
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestProjection()
    }
    private val overlays = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (AndroidSettings.canDrawOverlays(this)) {
            feedback = "Наложение разрешено. Нажмите «Начать перевод»"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        Settings.initialize(this)
        setContent { MysticScreen() }
    }

    override fun onStart() {
        super.onStart()
        Settings.activityVisible.value = true
    }

    override fun onStop() {
        Settings.activityVisible.value = false
        super.onStop()
    }

    private fun begin() {
        if (!AndroidSettings.canDrawOverlays(this)) {
            try {
                overlays.launch(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)))
                feedback = "Разрешите MysticTranslate показываться поверх других приложений"
            } catch (_: RuntimeException) {
                feedback = "В системных настройках разрешите MysticTranslate показываться поверх приложений"
            }
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("askedNotifications", false)
        ) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("askedNotifications", true).apply()
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else requestProjection()
    }

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val request = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(
            MediaProjectionConfig.createConfigForDefaultDisplay(),
        ) else manager.createScreenCaptureIntent()
        projection.launch(request)
    }

    private fun loadModel() {
        if (loadingModel) return
        lifecycleScope.launch {
            loadingModel = true
            feedback = "Проверяю и загружаю офлайн-модели…"
            val client = translator()
            try {
                client.downloadModelIfNeeded().await()
                feedback = "Офлайн-модели готовы"
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                feedback = "Не удалось загрузить модели. Проверьте интернет и повторите"
            } finally {
                client.close()
                loadingModel = false
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MysticScreen() {
        val options by Settings.current.collectAsState()
        val status by CaptureService.status.collectAsState()
        val history by Settings.dialogues.collectAsState()
        var page by rememberSaveable { mutableIntStateOf(0) }
        fun save(value: Options) = Settings.save(this, value)

        MaterialTheme(colorScheme = darkColorScheme(
            primary = Color(0xFFE0CCFF), onPrimary = Color(0xFF32204E),
            primaryContainer = Color(0xFF44315F), onPrimaryContainer = Color(0xFFF0E4FF),
            secondary = Color(0xFFFFCD9F), background = Color(0xFF140F1D),
            surface = Color(0xFF1C1628), surfaceVariant = Color(0xFF383047),
            onSurface = Color(0xFFF5EEFF), onSurfaceVariant = Color(0xFFD0C4DC),
        )) {
            Scaffold(topBar = { TopAppBar(title = { Text("MysticTranslate") }) }) { inset ->
                Column(Modifier.padding(inset)) {
                    TabRow(selectedTabIndex = page) {
                        listOf("Перевод", "Словарь", "История").forEachIndexed { index, label ->
                            Tab(selected = page == index, onClick = { page = index }, text = { Text(label) })
                        }
                    }
                    key(page) {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            when (page) {
                                0 -> {
                                    ElevatedCard(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text("Китайский → русский", style = MaterialTheme.typography.headlineSmall)
                                            Text(status.message)
                                            if (feedback.isNotBlank()) Text(feedback, color = MaterialTheme.colorScheme.secondary)
                                            Button(onClick = {
                                                if (status.active) stopService(Intent(this@MainActivity, CaptureService::class.java))
                                                else begin()
                                            }, enabled = !status.starting) {
                                                Text(if (status.active) "Остановить захват" else "Начать перевод")
                                            }
                                            OutlinedButton(onClick = ::loadModel, enabled = !loadingModel) {
                                                Text("Загрузить офлайн-модели")
                                            }
                                        }
                                    }
                                    Text("Область перевода", style = MaterialTheme.typography.titleLarge)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(options.dialogue, { save(options.copy(dialogue = true)) },
                                            { Text("Диалоги внизу") })
                                        FilterChip(!options.dialogue, { save(options.copy(dialogue = false)) },
                                            { Text("Весь экран") })
                                    }
                                    SettingSwitch("Повышенная детализация OCR", options.detailed) {
                                        save(options.copy(detailed = it))
                                    }
                                    ValueSlider("Интервал OCR · " + String.format(java.util.Locale.forLanguageTag("ru"), "%.1f с", options.interval),
                                        options.interval, 0.8f..5f, 20) { save(options.copy(interval = it)) }
                                    ValueSlider("Шрифт · " + options.font.toInt() + " sp", options.font, 12f..30f, 17) {
                                        save(options.copy(font = it))
                                    }
                                    ValueSlider("Непрозрачность · " + (options.opacity * 100).toInt() + "%",
                                        options.opacity, 0.2f..0.8f, 11) { save(options.copy(opacity = it)) }
                                    HorizontalDivider()
                                    NetworkSettings(options, ::save)
                                }
                                1 -> GlossarySettings(options, ::save)
                                else -> HistoryPage(history)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun NetworkSettings(options: Options, save: (Options) -> Unit) {
        var confirmation by remember { mutableStateOf("") }
        var umiUrl by remember { mutableStateOf(options.umiUrl) }
        var apiUrl by remember { mutableStateOf(options.apiUrl) }
        var model by remember { mutableStateOf(options.model) }
        var apiKey by remember { mutableStateOf(Settings.apiKey) }

        Text("Распознавание", style = MaterialTheme.typography.titleLarge)
        Text(if (options.umi) "Umi-OCR · свой сервер" else "ML Kit · китайский, на устройстве")
        OutlinedTextField(umiUrl, { umiUrl = it }, Modifier.fillMaxWidth(), enabled = !options.umi,
            singleLine = true, label = { Text("HTTPS-адрес Umi-OCR") })
        OutlinedButton(
            enabled = options.umi || httpsEndpoint(umiUrl, "api/ocr") != null,
            onClick = {
                if (options.umi) save(options.copy(umi = false)) else confirmation = "ocr"
            },
        ) { Text(if (options.umi) "Вернуться к ML Kit OCR" else "Выбрать Umi-OCR") }

        Text("Облачный перевод диалогов", style = MaterialTheme.typography.titleLarge)
        Text("Включается вручную. В режиме «Весь экран» переводит ML Kit.")
        OutlinedTextField(apiUrl, { apiUrl = it }, Modifier.fillMaxWidth(), enabled = !options.cloud,
            singleLine = true, label = { Text("HTTPS-адрес API, обычно с /v1") })
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), enabled = !options.cloud,
            singleLine = true, label = { Text("Название модели") })
        OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), enabled = !options.cloud,
            singleLine = true, label = { Text("API-ключ на этот сеанс") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation())
        OutlinedButton(
            enabled = options.cloud || (httpsEndpoint(apiUrl, "chat/completions") != null &&
                model.isNotBlank() && apiKey.trim().matches(Regex("[!-~]+"))),
            onClick = {
                if (options.cloud) {
                    Settings.apiKey = ""
                    apiKey = ""
                    save(options.copy(cloud = false))
                } else confirmation = "cloud"
            },
        ) { Text(if (options.cloud) "Выключить облако" else "Включить облако") }

        if (confirmation.isNotEmpty()) {
            val isOcr = confirmation == "ocr"
            AlertDialog(
                onDismissRequest = { confirmation = "" },
                title = { Text(if (isOcr) "Отправлять изображения на Umi-OCR?" else "Отправлять текст в облако?") },
                text = { Text(if (isOcr)
                    "Выбранный HTTPS-сервер получит кадры области распознавания. Для всего экрана сервер получает весь кадр, в том числе после переключения в другое приложение."
                else
                    "API получит распознанный китайский текст, до трёх недавних реплик с переводом и словарь. При переключении в другое приложение захват продолжается. Ключ хранится в памяти MysticTranslate.") },
                confirmButton = {
                    TextButton(onClick = {
                        if (isOcr) save(options.copy(umi = true, umiUrl = umiUrl.trim())) else {
                            Settings.apiKey = apiKey.trim()
                            save(options.copy(cloud = true, apiUrl = apiUrl.trim(), model = model.trim()))
                        }
                        confirmation = ""
                    }) { Text("Разрешить") }
                },
                dismissButton = { TextButton(onClick = { confirmation = "" }) { Text("Отмена") } },
            )
        }
    }

    @Composable
    private fun GlossarySettings(options: Options, save: (Options) -> Unit) {
        var draft by rememberSaveable { mutableStateOf(options.glossary) }
        var message by rememberSaveable { mutableStateOf("") }
        Text("Повелитель тайн", style = MaterialTheme.typography.headlineSmall)
        SettingSwitch("Применять словарь профиля", options.profile) { save(options.copy(profile = it)) }
        Text("Один термин на строку. Между китайским и русским вариантом ставьте знак =. Строки с # служат комментариями.")
        OutlinedTextField(draft, { draft = it.take(24_000) }, Modifier.fillMaxWidth(),
            label = { Text("Термины") }, minLines = 9)
        Button(onClick = {
            try {
                parseTerms(draft)
                save(options.copy(glossary = draft))
                message = "Словарь сохранён"
            } catch (error: IllegalArgumentException) {
                message = error.message.orEmpty()
            }
        }) { Text("Сохранить словарь") }
        if (message.isNotBlank()) Text(message)
    }

    @Composable
    private fun HistoryPage(history: List<Dialogue>) {
        var clear by remember { mutableStateOf(false) }
        Text("Последние диалоги", style = MaterialTheme.typography.headlineSmall)
        if (history.isEmpty()) Text("Здесь появятся переведённые реплики из нижней области экрана.")
        if (history.isNotEmpty()) TextButton(onClick = { clear = true }) { Text("Очистить историю") }
        history.forEach { entry ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(entry.russian, style = MaterialTheme.typography.bodyLarge)
                    Text(entry.source, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (clear) AlertDialog(
            onDismissRequest = { clear = false },
            title = { Text("Удалить сохранённые диалоги?") },
            confirmButton = {
                TextButton(onClick = { Settings.clearHistory(this@MainActivity); clear = false }) {
                    Text("Удалить")
                }
            },
            dismissButton = { TextButton(onClick = { clear = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked, change)
    }
}

@Composable
private fun ValueSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>,
                        steps: Int, change: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Column {
        Text(label)
        Slider(draft, { draft = it }, valueRange = range, steps = steps, onValueChangeFinished = { change(draft) })
    }
}
