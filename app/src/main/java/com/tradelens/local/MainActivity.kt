package com.tradelens.local

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.tradelens.local.ai.LlmEngine
import com.tradelens.local.ai.ModelState
import com.tradelens.local.data.AppSettings
import com.tradelens.local.data.SettingsRepository
import com.tradelens.local.service.OverlayService
import com.tradelens.local.ui.HistoryScreen
import com.tradelens.local.ui.OnboardingScreen
import com.tradelens.local.ui.SettingsScreen
import com.tradelens.local.ui.theme.TradeLensTheme
import com.tradelens.local.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val app: TradeLensApp
        get() = application as TradeLensApp

    private var importMessage by mutableStateOf("")
    private var permissionVersion by mutableIntStateOf(0)

    private val capturePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                OverlayService.startCapture(this, result.resultCode, data)
            }
            finish()
        }

    private val modelDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importModelFromUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TradeLensTheme {
                TradeLensRoot(
                    importMessage = importMessage,
                    permissionVersion = permissionVersion,
                    onChangeSettings = ::updateSettings,
                    onImportModel = { modelDocumentLauncher.launch(arrayOf("*/*")) },
                    onOpenAccessibilitySettings = {
                        Permissions.openAccessibilitySettings(this)
                    },
                    onOpenOverlaySettings = { Permissions.openOverlaySettings(this) },
                    onRequestNotifications = { Permissions.requestNotifications(this) },
                    onStartOverlay = { startOverlay() },
                    onAcceptDisclaimer = {
                        updateSettings { it.copy(disclaimerAccepted = true) }
                    }
                )
            }
        }
        handleCaptureRequest(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCaptureRequest(intent)
    }

    override fun onResume() {
        super.onResume()
        permissionVersion += 1
    }

    private fun handleCaptureRequest(intent: Intent?) {
        val requestIntent = intent ?: return
        if (requestIntent.action != ACTION_REQUEST_SCREEN_CAPTURE) return
        requestIntent.action = null
        window.decorView.post {
            lifecycleScope.launch {
                val current = app.settingsRepository.settings.first()
                if (!current.disclaimerAccepted) {
                    Toast.makeText(
                        this@MainActivity,
                        "Accept the educational-use disclaimer before analysis.",
                        Toast.LENGTH_LONG
                    ).show()
                } else if (!current.analysisEnabled) {
                    Toast.makeText(
                        this@MainActivity,
                        "Enable analysis in Settings before using the overlay.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val manager =
                        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    capturePermissionLauncher.launch(manager.createScreenCaptureIntent())
                }
            }
        }
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        lifecycleScope.launch {
            app.settingsRepository.update(transform)
        }
    }

    private fun startOverlay() {
        if (!Permissions.canDrawOverlays(this)) {
            Permissions.openOverlaySettings(this)
            return
        }
        OverlayService.start(this)
    }

    private fun importModelFromUri(uri: android.net.Uri) {
        lifecycleScope.launch {
            val settings = app.settingsRepository.settings.first()
            val temporary = File(cacheDir, "gemma-model-import.tmp")
            val destination = File(filesDir, SettingsRepository.MODEL_FILE_NAME)
            importMessage = "Copying model into app storage…"
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        temporary.outputStream().buffered().use { output ->
                            input.copyTo(output, DEFAULT_BUFFER_SIZE * 8)
                        }
                    } ?: error("The selected file could not be opened.")
                }
                val valid = LlmEngine.validateModel(
                    context = this@MainActivity,
                    modelPath = temporary.absolutePath,
                    settings = settings
                )
                if (!valid) {
                    temporary.delete()
                    importMessage = (LlmEngine.modelState.value as? ModelState.Error)
                        ?.message ?: "The selected model could not be loaded."
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                app.settingsRepository.setModelReady(true)
                importMessage = "Imported model and verified it for on-device use."
            } catch (error: Throwable) {
                temporary.delete()
                importMessage = error.message ?: "Could not import the selected model."
            }
        }
    }

    companion object {
        const val ACTION_REQUEST_SCREEN_CAPTURE =
            "com.tradelens.local.action.REQUEST_SCREEN_CAPTURE"
    }
}

@Composable
private fun TradeLensRoot(
    importMessage: String,
    permissionVersion: Int,
    onChangeSettings: ((AppSettings) -> AppSettings) -> Unit,
    onImportModel: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRequestNotifications: () -> Unit,
    onStartOverlay: () -> Unit,
    onAcceptDisclaimer: () -> Unit
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as TradeLensApp
    val settings by app.settingsRepository.settings.collectAsState(initial = AppSettings())
    val modelState by LlmEngine.modelState.collectAsState()
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf("Home") }
    val accessibilityEnabled = remember(permissionVersion) {
        Permissions.isAccessibilityEnabled(app)
    }
    val overlayGranted = remember(permissionVersion) {
        Permissions.canDrawOverlays(app)
    }

    if (!settings.disclaimerAccepted) {
        OnboardingScreen(
            settings = settings,
            accessibilityEnabled = accessibilityEnabled,
            overlayPermissionGranted = overlayGranted,
            onSettingsChange = onChangeSettings,
            onImportModel = onImportModel,
            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
            onOpenOverlaySettings = onOpenOverlaySettings,
            onRequestNotifications = onRequestNotifications,
            onAcceptDisclaimer = onAcceptDisclaimer
        )
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf("Home", "Settings", "History").forEach { page ->
                    NavigationBarItem(
                        selected = destination == page,
                        onClick = { destination = page },
                        icon = { Text(page.take(1)) },
                        label = { Text(page) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (destination) {
                "Settings" -> SettingsScreen(
                    settings = settings,
                    onSettingsChange = onChangeSettings,
                    onImportModel = onImportModel,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpenOverlaySettings = onOpenOverlaySettings
                )

                "History" -> HistoryScreen(app.database.analysisDao())

                else -> HomeScreen(
                    settings = settings,
                    modelState = modelState,
                    accessibilityEnabled = accessibilityEnabled,
                    overlayGranted = overlayGranted,
                    importMessage = importMessage,
                    onOpenSettings = { destination = "Settings" },
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpenOverlaySettings = onOpenOverlaySettings,
                    onStartOverlay = onStartOverlay,
                    onEnableAnalysis = {
                        onChangeSettings { it.copy(analysisEnabled = true) }
                    },
                    onStopAnalysis = {
                        onChangeSettings { it.copy(analysisEnabled = false) }
                        scope.launch { LlmEngine.release() }
                    }
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    settings: AppSettings,
    modelState: ModelState,
    accessibilityEnabled: Boolean,
    overlayGranted: Boolean,
    importMessage: String,
    onOpenSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onStartOverlay: () -> Unit,
    onEnableAnalysis: () -> Unit,
    onStopAnalysis: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        Text("TradeLens Local", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Read-only screen analysis. Inference and history stay on this device.",
            style = MaterialTheme.typography.bodyLarge
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(17.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text("Setup status", style = MaterialTheme.typography.titleLarge)
                Text("Local model: ${modelStatus(settings, modelState)}")
                Text("Accessibility: ${if (accessibilityEnabled) "On" else "Off"}")
                Text("Overlay permission: ${if (overlayGranted) "Allowed" else "Not allowed"}")
                Text("Target package: ${settings.targetPackage.ifBlank { "Not set" }}")
                Text("Analysis: ${if (settings.analysisEnabled) "Enabled" else "Disabled"}")
            }
        }
        if (importMessage.isNotBlank()) {
            Text(importMessage, style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onStartOverlay, modifier = Modifier.fillMaxWidth()) {
            Text("Start floating overlay")
        }
        if (!accessibilityEnabled) {
            OutlinedButton(
                onClick = onOpenAccessibilitySettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Enable Accessibility service")
            }
        }
        if (!overlayGranted) {
            OutlinedButton(
                onClick = onOpenOverlaySettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Allow floating overlay")
            }
        }
        if (settings.analysisEnabled) {
            OutlinedButton(onClick = onStopAnalysis, modifier = Modifier.fillMaxWidth()) {
                Text("Disable analysis and unload model")
            }
        } else {
            OutlinedButton(onClick = onEnableAnalysis, modifier = Modifier.fillMaxWidth()) {
                Text("Enable analysis")
            }
        }
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Model and analysis settings")
        }
        Text(
            "For a manual screen read, tap the floating TL bubble and approve Android's " +
                "one-time screen-capture prompt. Accessibility capture only reads the configured " +
                "trading app; it never controls it.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun modelStatus(settings: AppSettings, modelState: ModelState): String = when {
    modelState is ModelState.Loading -> "Loading"
    modelState is ModelState.Error -> "Error: ${modelState.message}"
    settings.modelReady -> "Ready"
    else -> "Not downloaded"
}
