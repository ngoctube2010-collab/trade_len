package com.tradelens.local.ai

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.SystemClock
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.tradelens.local.data.AppSettings
import com.tradelens.local.data.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

sealed interface ModelState {
    data object Unloaded : ModelState
    data object Loading : ModelState
    data object Ready : ModelState
    data class Error(val message: String) : ModelState
}

data class GpuCapability(
    val renderer: String = "Checking renderer",
    val supported: Boolean = false
)

data class AnalysisRunResult(
    val output: AnalysisOutput?,
    val rawResponse: String,
    val inferenceMs: Long,
    val parseError: Boolean
)

object LlmEngine {
    private val _modelState = MutableStateFlow<ModelState>(ModelState.Unloaded)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val _gpuCapability = MutableStateFlow(GpuCapability())
    val gpuCapability: StateFlow<GpuCapability> = _gpuCapability.asStateFlow()

    private val engineMutex = Mutex()
    private var inference: LlmInference? = null
    private var activeResponse: CompletableDeferred<String>? = null
    private var loadedPath: String? = null
    private var loadedMaxTokens: Int? = null
    private var loadedTopK: Int? = null
    private var loadedTemperature: Float? = null

    suspend fun detectGpuCapability(): GpuCapability = withContext(Dispatchers.Default) {
        val renderer = readGlRenderer() ?: "Unknown renderer"
        val capability = GpuCapability(
            renderer = renderer,
            supported = renderer.contains("Adreno", ignoreCase = true)
        )
        _gpuCapability.value = capability
        capability
    }

    suspend fun validateModel(
        context: Context,
        modelPath: String = File(context.filesDir, SettingsRepository.MODEL_FILE_NAME).absolutePath,
        settings: AppSettings
    ): Boolean = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            _modelState.value = ModelState.Loading
            val model = File(modelPath)
            if (!model.isFile || model.length() == 0L) {
                _modelState.value = ModelState.Error("Model file is missing or empty.")
                return@withLock false
            }
            try {
                inference?.close()
                inference = null
                createInference(context.applicationContext, modelPath, settings).close()
                _modelState.value = ModelState.Ready
                true
            } catch (error: Throwable) {
                _modelState.value =
                    ModelState.Error(error.message ?: "The model could not be loaded.")
                false
            }
        }
    }

    suspend fun analyze(
        context: Context,
        settings: AppSettings,
        screenText: String
    ): AnalysisRunResult {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            withTimeout(INFERENCE_TIMEOUT_MS) {
                val first = generate(context, settings, AnalysisPrompt.build(screenText))
                val parsedFirst = AnalysisPrompt.parse(first)
                if (parsedFirst != null) {
                    AnalysisRunResult(
                        output = parsedFirst,
                        rawResponse = first,
                        inferenceMs = SystemClock.elapsedRealtime() - startedAt,
                        parseError = false
                    )
                } else {
                    val retryResponse = generate(context, settings, AnalysisPrompt.retry(screenText))
                    AnalysisRunResult(
                        output = AnalysisPrompt.parse(retryResponse),
                        rawResponse = retryResponse,
                        inferenceMs = SystemClock.elapsedRealtime() - startedAt,
                        parseError = AnalysisPrompt.parse(retryResponse) == null
                    )
                }
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.TimeoutCancellationException) {
                _modelState.value = ModelState.Error("Inference exceeded the 60-second limit.")
                return AnalysisRunResult(
                    output = null,
                    rawResponse = "Inference exceeded the 60-second limit.",
                    inferenceMs = SystemClock.elapsedRealtime() - startedAt,
                    parseError = false
                )
            }
            if (error is kotlinx.coroutines.CancellationException) throw error
            _modelState.value = ModelState.Error(
                error.message ?: "On-device inference failed."
            )
            AnalysisRunResult(
                output = null,
                rawResponse = error.message ?: "On-device inference failed.",
                inferenceMs = SystemClock.elapsedRealtime() - startedAt,
                parseError = false
            )
        }
    }

    suspend fun release() {
        engineMutex.withLock {
            releaseWithoutLock()
        }
    }

    @Synchronized
    fun releaseImmediately() {
        releaseWithoutLock()
    }

    private suspend fun generate(
        context: Context,
        settings: AppSettings,
        prompt: String
    ): String = engineMutex.withLock {
        val modelPath = File(context.filesDir, SettingsRepository.MODEL_FILE_NAME).absolutePath
        ensureLoaded(context.applicationContext, modelPath, settings)
        val target = inference ?: error("The local model is not loaded.")
        val completion = CompletableDeferred<String>()
        activeResponse = completion
        try {
            withContext(Dispatchers.Default) {
                target.generateResponseAsync(prompt)
            }
            completion.await()
        } finally {
            if (activeResponse === completion) activeResponse = null
            if (!completion.isCompleted) {
                inference?.close()
                inference = null
                loadedPath = null
                _modelState.value = ModelState.Unloaded
            }
        }
    }

    private suspend fun ensureLoaded(
        context: Context,
        modelPath: String,
        settings: AppSettings
    ) {
        val existingMatches = inference != null &&
            loadedPath == modelPath &&
            loadedMaxTokens == settings.maxTokens &&
            loadedTopK == settings.topK &&
            loadedTemperature == settings.temperature
        if (existingMatches) return

        inference?.close()
        inference = null
        val file = File(modelPath)
        check(file.isFile && file.length() > 0L) { "Download or import the local model first." }
        _modelState.value = ModelState.Loading
        inference = withContext(Dispatchers.IO) {
            createInference(context, modelPath, settings)
        }
        loadedPath = modelPath
        loadedMaxTokens = settings.maxTokens
        loadedTopK = settings.topK
        loadedTemperature = settings.temperature
        _modelState.value = ModelState.Ready
    }

    @Synchronized
    private fun releaseWithoutLock() {
        inference?.close()
        inference = null
        activeResponse = null
        loadedPath = null
        loadedMaxTokens = null
        loadedTopK = null
        loadedTemperature = null
        _modelState.value = ModelState.Unloaded
    }

    private fun createInference(
        context: Context,
        modelPath: String,
        settings: AppSettings
    ): LlmInference {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(settings.maxTokens)
            .setTopK(settings.topK)
            .setTemperature(settings.temperature)
            .setResultListener { partialResult, done ->
                if (done) activeResponse?.complete(partialResult)
            }
            .setErrorListener { error ->
                activeResponse?.completeExceptionally(error)
            }
            .build()
        return LlmInference.createFromOptions(context, options)
    }

    private fun readGlRenderer(): String? {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null
            val versions = IntArray(2)
            if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) return null
            val attributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) ||
                count[0] == 0
            ) return null
            val config = configs[0] ?: return null
            val contextAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                contextAttributes,
                0
            )
            val surfaceAttributes = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            )
            surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttributes, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE) return null
            if (!EGL14.eglMakeCurrent(display, surface, surface, eglContext)) return null
            return GLES20.glGetString(GLES20.GL_RENDERER)
        } catch (_: Throwable) {
            return null
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
                EGL14.eglTerminate(display)
            }
        }
    }

    private const val INFERENCE_TIMEOUT_MS = 60_000L
}
