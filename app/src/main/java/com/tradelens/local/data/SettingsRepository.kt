package com.tradelens.local.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.tradeLensDataStore by preferencesDataStore(name = "tradelens_settings")

data class AppSettings(
    val disclaimerAccepted: Boolean = false,
    val modelUrl: String = SettingsRepository.DEFAULT_MODEL_URL,
    val targetPackage: String = "",
    val analysisEnabled: Boolean = false,
    val modelReady: Boolean = false,
    val maxTokens: Int = 256,
    val topK: Int = 40,
    val temperature: Float = 0.3f,
    val useGpu: Boolean = false
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val disclaimerAccepted = booleanPreferencesKey("disclaimer_accepted")
        val modelUrl = stringPreferencesKey("model_url")
        val targetPackage = stringPreferencesKey("target_package")
        val analysisEnabled = booleanPreferencesKey("analysis_enabled")
        val modelReady = booleanPreferencesKey("model_ready")
        val maxTokens = intPreferencesKey("max_tokens")
        val topK = intPreferencesKey("top_k")
        val temperature = floatPreferencesKey("temperature")
        val useGpu = booleanPreferencesKey("use_gpu")
    }

    val settings: Flow<AppSettings> = context.tradeLensDataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(androidx.datastore.preferences.core.emptyPreferences())
            }
            else throw error
        }
        .map { prefs ->
            AppSettings(
                disclaimerAccepted = prefs[Keys.disclaimerAccepted] ?: false,
                modelUrl = prefs[Keys.modelUrl] ?: DEFAULT_MODEL_URL,
                targetPackage = prefs[Keys.targetPackage] ?: "",
                analysisEnabled = prefs[Keys.analysisEnabled] ?: false,
                modelReady = prefs[Keys.modelReady] ?: false,
                maxTokens = (prefs[Keys.maxTokens] ?: 256).coerceIn(64, 512),
                topK = (prefs[Keys.topK] ?: 40).coerceIn(1, 64),
                temperature = (prefs[Keys.temperature] ?: 0.3f).coerceIn(0f, 1.5f),
                useGpu = prefs[Keys.useGpu] ?: false
            )
        }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.tradeLensDataStore.edit { prefs ->
            val current = AppSettings(
                disclaimerAccepted = prefs[Keys.disclaimerAccepted] ?: false,
                modelUrl = prefs[Keys.modelUrl] ?: DEFAULT_MODEL_URL,
                targetPackage = prefs[Keys.targetPackage] ?: "",
                analysisEnabled = prefs[Keys.analysisEnabled] ?: false,
                modelReady = prefs[Keys.modelReady] ?: false,
                maxTokens = (prefs[Keys.maxTokens] ?: 256).coerceIn(64, 512),
                topK = (prefs[Keys.topK] ?: 40).coerceIn(1, 64),
                temperature = (prefs[Keys.temperature] ?: 0.3f).coerceIn(0f, 1.5f),
                useGpu = prefs[Keys.useGpu] ?: false
            )
            val changed = transform(current)
            prefs[Keys.disclaimerAccepted] = changed.disclaimerAccepted
            prefs[Keys.modelUrl] = changed.modelUrl
            prefs[Keys.targetPackage] = changed.targetPackage
            prefs[Keys.analysisEnabled] = changed.analysisEnabled
            prefs[Keys.modelReady] = changed.modelReady
            prefs[Keys.maxTokens] = changed.maxTokens.coerceIn(64, 512)
            prefs[Keys.topK] = changed.topK.coerceIn(1, 64)
            prefs[Keys.temperature] = changed.temperature.coerceIn(0f, 1.5f)
            prefs[Keys.useGpu] = changed.useGpu
        }
    }

    suspend fun setModelReady(ready: Boolean) {
        update { it.copy(modelReady = ready) }
    }

    companion object {
        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/google/gemma-2b-it-gpu-int4/resolve/main/gemma2-2b-it-gpu-int4.bin"
        const val MODEL_FILE_NAME = "gemma2-2b-it-gpu-int4.bin"
    }
}
