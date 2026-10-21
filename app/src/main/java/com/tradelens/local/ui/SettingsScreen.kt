package com.tradelens.local.ui

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tradelens.local.TradeLensApp
import com.tradelens.local.ai.LlmEngine
import com.tradelens.local.ai.ModelDownloader
import com.tradelens.local.ai.ModelState
import com.tradelens.local.data.AppSettings
import com.tradelens.local.data.SettingsRepository
import com.tradelens.local.util.Permissions
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onImportModel: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gpuCapability by LlmEngine.gpuCapability.collectAsState()
    val modelState by LlmEngine.modelState.collectAsState()

    LaunchedEffect(Unit) {
        LlmEngine.detectGpuCapability()
    }
    LaunchedEffect(gpuCapability.supported, settings.useGpu) {
        if (!gpuCapability.supported && settings.useGpu) {
            onSettingsChange { it.copy(useGpu = false) }
        }
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Model, permissions, and local inference controls",
                style = MaterialTheme.typography.bodyMedium
            )
            ModelManagementSection(
                settings = settings,
                onSettingsChange = onSettingsChange,
                onImportModel = onImportModel,
                modifier = Modifier.fillMaxWidth()
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Screen reader", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = settings.targetPackage,
                        onValueChange = { value ->
                            onSettingsChange { it.copy(targetPackage = value.trim()) }
                        },
                        label = { Text("Target app package(s)") },
                        placeholder = { Text("e.g. com.tradingview.tradingviewapp") },
                        supportingText = {
                            Text("Only configured packages are observed; comma-separated for multiple. Read-only.")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Target app package presets
                    Text("Popular trading apps:", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "TradingView" to "com.tradingview.tradingviewapp",
                            "Binance" to "com.binance.dev",
                            "Webull" to "org.dayup.stocks",
                            "MetaTrader 5" to "net.metaquotes.metatrader5",
                            "Robinhood" to "com.robinhood.android",
                            "Interactive Brokers" to "com.interactivebrokers.mobile.charlotte"
                        ).forEach { (name, pkg) ->
                            AssistChip(
                                onClick = { onSettingsChange { it.copy(targetPackage = pkg) } },
                                label = { Text(name) }
                            )
                        }
                    }

                    val firstPkg = remember(settings.targetPackage) {
                        settings.targetPackage.split(',', ';', ' ', '\n').firstOrNull { it.isNotBlank() } ?: ""
                    }
                    val isInstalled = remember(firstPkg, context) {
                        if (firstPkg.isBlank()) null
                        else runCatching { context.packageManager.getPackageInfo(firstPkg, 0) }.isSuccess
                    }
                    if (isInstalled == true) {
                        Text("✓ App is installed on this device", color = Color(0xFF2E7D32), style = MaterialTheme.typography.bodySmall)
                    } else if (isInstalled == false) {
                        Text("App not detected on device (verify package name)", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    SettingSwitch(
                        title = "Enable analysis",
                        summary = "Analyze changed screens locally when the service is active.",
                        checked = settings.analysisEnabled,
                        onCheckedChange = { enabled ->
                            onSettingsChange { it.copy(analysisEnabled = enabled) }
                            if (!enabled) {
                                scope.launch {
                                    LlmEngine.release()
                                }
                            }
                        }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onOpenAccessibilitySettings,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Accessibility")
                        }
                        OutlinedButton(
                            onClick = onOpenOverlaySettings,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Overlay permission")
                        }
                    }
                    Button(
                        onClick = {
                            if (Permissions.canDrawOverlays(context)) {
                                com.tradelens.local.service.OverlayService.start(context)
                            } else {
                                onOpenOverlaySettings()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Start floating overlay")
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Generation", style = MaterialTheme.typography.titleLarge)
                    Text("Maximum tokens: ${settings.maxTokens}")
                    Slider(
                        value = settings.maxTokens.toFloat(),
                        onValueChange = { value ->
                            onSettingsChange { it.copy(maxTokens = value.toInt().coerceIn(64, 512)) }
                        },
                        valueRange = 64f..512f,
                        steps = 6
                    )
                    Text("Top K: ${settings.topK}")
                    Slider(
                        value = settings.topK.toFloat(),
                        onValueChange = { value ->
                            onSettingsChange { it.copy(topK = value.toInt().coerceIn(1, 64)) }
                        },
                        valueRange = 1f..64f,
                        steps = 62
                    )
                    Text("Temperature: ${"%.1f".format(settings.temperature)}")
                    Slider(
                        value = settings.temperature,
                        onValueChange = { value ->
                            onSettingsChange { it.copy(temperature = value.coerceIn(0f, 1.5f)) }
                        },
                        valueRange = 0f..1.5f,
                        steps = 14
                    )
                    Text(
                        "Defaults: 256 tokens, Top K 40, temperature 0.3. Each analysis " +
                            "has a 60-second time limit.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Inference backend", style = MaterialTheme.typography.titleLarge)
                    SettingSwitch(
                        title = "Request GPU backend",
                        summary = if (gpuCapability.supported) {
                            "Detected ${gpuCapability.renderer}. The MediaPipe 0.10.14 API " +
                                "does not expose an explicit backend selector; model format " +
                                "determines delegate behavior."
                        } else {
                            "GPU is disabled unless GL_RENDERER contains Adreno. The SDK uses " +
                                "model/runtime behavior rather than an explicit backend switch. " +
                                "Detected: ${gpuCapability.renderer}."
                        },
                        checked = settings.useGpu,
                        enabled = gpuCapability.supported,
                        onCheckedChange = { useGpu ->
                            onSettingsChange { it.copy(useGpu = useGpu) }
                        },
                        onDisabledClick = {
                            Toast.makeText(
                                context,
                                "GPU is disabled unless GL_RENDERER contains Adreno.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                    if (modelState is ModelState.Error) {
                        Text(
                            (modelState as ModelState.Error).message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Text(
                "Privacy: screen text, model files, and analysis history are stored on this device. " +
                    "The app does not send telemetry.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun ModelManagementSection(
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onImportModel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as TradeLensApp
    val scope = rememberCoroutineScope()
    val modelState by LlmEngine.modelState.collectAsState()
    var downloading by remember { mutableStateOf(false) }
    var operationError by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var statusMessage by remember { mutableStateOf("") }
    val modelFile = remember(context) {
        File(context.filesDir, SettingsRepository.MODEL_FILE_NAME)
    }
    val partialFile = remember(modelFile) {
        File(modelFile.parentFile, "${modelFile.name}.part")
    }

    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Local model", style = MaterialTheme.typography.titleLarge)
            val status = when {
                downloading -> "Downloading ${"%.0f".format(progress * 100)}%"
                modelState is ModelState.Loading -> "Loading and validating"
                modelState is ModelState.Error -> "Error"
                settings.modelReady && modelFile.isFile -> "Ready"
                operationError -> "Error"
                partialFile.isFile -> "Not downloaded (an interrupted download can resume)"
                modelFile.isFile -> "Model file found; validation is required"
                else -> "Not downloaded"
            }
            val fileSizeInfo = remember(modelFile, settings.modelReady) {
                if (modelFile.isFile && modelFile.length() > 0L) {
                    val mb = modelFile.length() / (1024.0 * 1024.0)
                    if (mb >= 1000.0) " (%.2f GB)".format(mb / 1024.0) else " (%.0f MB)".format(mb)
                } else if (partialFile.isFile && partialFile.length() > 0L) {
                    val mb = partialFile.length() / (1024.0 * 1024.0)
                    " (partial: %.0f MB)".format(mb)
                } else ""
            }
            Text("Status: $status$fileSizeInfo", style = MaterialTheme.typography.bodyMedium)
            if (downloading) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (modelState is ModelState.Error) {
                Text(
                    (modelState as ModelState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (statusMessage.isNotBlank()) {
                Text(statusMessage, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = settings.modelUrl,
                onValueChange = { url -> onSettingsChange { it.copy(modelUrl = url) } },
                label = { Text("Model download URL") },
                singleLine = false,
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { onSettingsChange { it.copy(modelUrl = SettingsRepository.DEFAULT_MODEL_URL) } },
                    label = { Text("Gemma 2B GPU URL") }
                )
                AssistChip(
                    onClick = { onSettingsChange { it.copy(modelUrl = SettingsRepository.CPU_MODEL_URL) } },
                    label = { Text("Gemma 2B CPU URL") }
                )
            }
            Text(
                "The default Gemma 2B int4 URL may require Hugging Face access approval. " +
                    "It is a GPU-format URL; on Mali/PowerVR use a CPU-compatible model URL or " +
                    "import. Download is the only network operation in the app.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            downloading = true
                            operationError = false
                            progress = 0f
                            statusMessage = ""
                            try {
                                ModelDownloader.download(settings.modelUrl, modelFile).collect { update ->
                                    progress = update.fraction ?: progress
                                }
                                val valid = LlmEngine.validateModel(
                                    context = context,
                                    modelPath = modelFile.absolutePath,
                                    settings = settings
                                )
                                app.settingsRepository.setModelReady(valid)
                                if (!valid) {
                                    operationError = true
                                    modelFile.delete()
                                }
                                statusMessage = if (valid) {
                                    "Model verified and ready for offline use."
                                } else {
                                    (LlmEngine.modelState.value as? ModelState.Error)
                                        ?.message ?: "Model verification failed."
                                }
                            } catch (error: Throwable) {
                                operationError = true
                                statusMessage = error.message ?: "Model download failed."
                                app.settingsRepository.setModelReady(modelFile.isFile)
                            } finally {
                                downloading = false
                            }
                        }
                    },
                    enabled = !downloading && settings.modelUrl.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (downloading) "Downloading…" else "Download model")
                }
                OutlinedButton(
                    onClick = onImportModel,
                    enabled = !downloading,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Import from device")
                }
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        LlmEngine.release()
                        ModelDownloader.delete(modelFile)
                        app.settingsRepository.setModelReady(false)
                        operationError = false
                        statusMessage = "Local model deleted."
                    }
                },
                enabled = !downloading && (modelFile.exists() || partialFile.exists()),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Delete model")
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
    onDisabledClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = if (enabled) Modifier else Modifier.padding(top = 4.dp)
        )
    }
    if (!enabled && onDisabledClick != null) {
        OutlinedButton(onClick = onDisabledClick, modifier = Modifier.fillMaxWidth()) {
            Text("Why is GPU unavailable?")
        }
    }
}
