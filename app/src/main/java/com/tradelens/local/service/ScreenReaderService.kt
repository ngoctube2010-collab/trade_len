package com.tradelens.local.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock
import com.tradelens.local.TradeLensApp
import com.tradelens.local.ai.LlmEngine
import com.tradelens.local.ai.toDisplayText
import com.tradelens.local.data.AnalysisEntity
import com.tradelens.local.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

object ScreenReaderState {
    private val mutableText = MutableStateFlow("")
    val text = mutableText.asStateFlow()

    internal fun publish(value: String) {
        mutableText.value = value
    }
}

class ScreenReaderService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var settings = AppSettings()
    private var settingsJob: Job? = null
    private var analysisJob: Job? = null
    private var lastHash = ""
    private var lastInferenceAt = 0L
    private var pendingScreenText = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        val repository = (application as TradeLensApp).settingsRepository
        settingsJob = serviceScope.launch {
            repository.settings.collectLatest { settings = it }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType !in setOf(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            )
        ) return
        val packageName = event.packageName?.toString() ?: return
        val configuredPackage = settings.targetPackage.trim()
        if (!settings.disclaimerAccepted ||
            configuredPackage.isEmpty() ||
            packageName != configuredPackage
        ) return

        val root = rootInActiveWindow ?: return
        val text = try {
            val builder = StringBuilder()
            appendNodeText(root, builder)
            builder.toString().take(MAX_SCREEN_TEXT_CHARS).trim()
        } finally {
            root.recycle()
        }
        if (text.isEmpty()) return

        val hash = sha256(text)
        if (hash == lastHash) return
        lastHash = hash
        pendingScreenText = text
        ScreenReaderState.publish(text)
        scheduleInference(hash, text)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        analysisJob?.cancel()
        settingsJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun scheduleInference(hash: String, text: String) {
        analysisJob?.cancel()
        analysisJob = serviceScope.launch {
            delay(SCREEN_DEBOUNCE_MS)
            if (hash != lastHash || text != pendingScreenText) return@launch
            if (lastInferenceAt > 0L &&
                SystemClock.elapsedRealtime() - lastInferenceAt < MINIMUM_INFERENCE_INTERVAL_MS
            ) return@launch
            val current = settings
            if (!current.disclaimerAccepted || !current.analysisEnabled) return@launch
            if (current.targetPackage.isBlank()) return@launch

            lastInferenceAt = SystemClock.elapsedRealtime()
            OverlayService.showLoading(applicationContext)
            val result = LlmEngine.analyze(applicationContext, current, text)
            val output = result.output
            withContext(Dispatchers.IO) {
                (application as TradeLensApp).database.analysisDao().insert(
                    AnalysisEntity(
                        ticker = output?.ticker.orEmpty(),
                        price = output?.price.orEmpty(),
                        aiSummary = output?.toDisplayText() ?: if (result.parseError) {
                            "${result.rawResponse}\n\nParse error"
                        } else {
                            result.rawResponse
                        },
                        risk = output?.risk ?: 0,
                        rawScreenText = text,
                        inferenceMs = result.inferenceMs
                    )
                )
            }
            OverlayService.showResult(
                context = applicationContext,
                ticker = output?.ticker.orEmpty(),
                price = output?.price.orEmpty(),
                summary = output?.toDisplayText() ?: if (result.parseError) {
                    "${result.rawResponse}\n\nParse error"
                } else {
                    result.rawResponse
                },
                risk = output?.risk ?: 0,
                parseError = result.parseError,
                inferenceMs = result.inferenceMs
            )
        }
    }

    private fun appendNodeText(node: AccessibilityNodeInfo, output: StringBuilder) {
        if (output.length >= MAX_SCREEN_TEXT_CHARS) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            output.append(it).append('\n')
        }
        if (output.length < MAX_SCREEN_TEXT_CHARS) {
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                output.append(it).append('\n')
            }
        }
        for (index in 0 until node.childCount) {
            if (output.length >= MAX_SCREEN_TEXT_CHARS) break
            val child = node.getChild(index) ?: continue
            try {
                appendNodeText(child, output)
            } finally {
                child.recycle()
            }
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val MAX_SCREEN_TEXT_CHARS = 4_000
        const val SCREEN_DEBOUNCE_MS = 350L
        const val MINIMUM_INFERENCE_INTERVAL_MS = 10_000L
    }
}
