package com.tradelens.local.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tradelens.local.MainActivity
import com.tradelens.local.R
import com.tradelens.local.TradeLensApp
import com.tradelens.local.ai.LlmEngine
import com.tradelens.local.ai.toDisplayText
import com.tradelens.local.data.AnalysisEntity
import com.tradelens.local.ocr.ScreenCaptureManager
import com.tradelens.local.util.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OverlayService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var cardView: View? = null
    private var cardParams: WindowManager.LayoutParams? = null
    private var cardDismissRunnable: Runnable? = null
    private var isCapturing = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForegroundWithType(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (Permissions.canDrawOverlays(this)) showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAPTURE -> {
                val data = parcelableIntent(intent, EXTRA_RESULT_DATA)
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                if (data == null) {
                    showError("Screen capture permission was not received.")
                } else {
                    captureAndAnalyze(resultCode, data)
                }
            }

            ACTION_LOADING -> showLoading()
            ACTION_RESULT -> showIntentResult(intent)
            ACTION_ERROR -> showError(intent.getStringExtra(EXTRA_SUMMARY).orEmpty())
            ACTION_START -> if (Permissions.canDrawOverlays(this)) showBubble()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cardDismissRunnable?.let(mainHandler::removeCallbacks)
        removeOverlays()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun captureAndAnalyze(resultCode: Int, resultData: Intent) {
        if (isCapturing) return
        isCapturing = true
        startForegroundWithType(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        showLoading("Capturing screen for this manual analysis…")
        serviceScope.launch {
            try {
                kotlinx.coroutines.delay(CAPTURE_FOREGROUND_SETTLE_MS)
                val text = ScreenCaptureManager.captureAndRecognize(
                    applicationContext,
                    resultCode,
                    resultData
                ).take(MAX_SCREEN_TEXT_CHARS)
                if (text.isBlank()) {
                    showError("No readable text was found on the captured screen.")
                } else {
                    analyzeAndSave(text)
                }
            } catch (error: Throwable) {
                showError(error.message ?: "Screen capture or OCR failed.")
            } finally {
                isCapturing = false
                startForegroundWithType(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        }
    }

    private suspend fun analyzeAndSave(screenText: String) {
        val app = application as TradeLensApp
        val settings = app.settingsRepository.settings.first()
        if (!settings.disclaimerAccepted) {
            showError("Accept the educational-use disclaimer before analyzing a screen.")
            return
        }
        if (!settings.analysisEnabled) {
            showError("Enable analysis in Settings before using the overlay.")
            return
        }
        showLoading("Running local analysis…")
        val result = LlmEngine.analyze(applicationContext, settings, screenText)
        val output = result.output
        val summary = output?.toDisplayText() ?: if (result.parseError) {
            "${result.rawResponse}\n\nParse error"
        } else {
            result.rawResponse
        }
        withContext(Dispatchers.IO) {
            app.database.analysisDao().insert(
                AnalysisEntity(
                    ticker = output?.ticker.orEmpty(),
                    price = output?.price.orEmpty(),
                    aiSummary = summary,
                    risk = output?.risk ?: 0,
                    rawScreenText = screenText,
                    inferenceMs = result.inferenceMs
                )
            )
        }
        showResult(
            context = applicationContext,
            ticker = output?.ticker.orEmpty(),
            price = output?.price.orEmpty(),
            summary = summary,
            risk = output?.risk ?: 0,
            parseError = result.parseError,
            inferenceMs = result.inferenceMs
        )
    }

    private fun showBubble() {
        if (bubbleView != null || !Permissions.canDrawOverlays(this)) return
        val view = layoutInflater.inflate(R.layout.overlay_bubble, null)
        val preferences = getSharedPreferences(POSITION_PREFS, MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            dp(48),
            dp(48),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.getInt(POSITION_X, dp(20))
            y = preferences.getInt(POSITION_Y, dp(180))
        }
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF1D6F5A.toInt())
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(5)) moved = true
                    if (moved) {
                        params.x = (startX + dx).coerceAtLeast(0)
                        params.y = (startY + dy).coerceAtLeast(0)
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    preferences.edit().putInt(POSITION_X, params.x)
                        .putInt(POSITION_Y, params.y).apply()
                    if (!moved) requestManualCapture()
                    true
                }

                else -> true
            }
        }
        try {
            windowManager.addView(view, params)
            bubbleView = view
            bubbleParams = params
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun requestManualCapture() {
        val activity = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_REQUEST_SCREEN_CAPTURE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(activity)
    }

    private fun showLoading(message: String = "Preparing local analysis…") {
        val card = ensureCard()
        card.findViewById<ProgressBar>(R.id.overlayProgress).visibility = View.VISIBLE
        card.findViewById<TextView>(R.id.overlayTitle).text = "Analyzing on this device"
        card.findViewById<TextView>(R.id.overlayDetails).text = message
        card.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        val card = ensureCard()
        card.findViewById<ProgressBar>(R.id.overlayProgress).visibility = View.GONE
        card.findViewById<TextView>(R.id.overlayTitle).text = "Analysis unavailable"
        card.findViewById<TextView>(R.id.overlayDetails).text = message
        card.visibility = View.VISIBLE
        scheduleDismiss()
    }

    private fun showIntentResult(intent: Intent) {
        renderResult(
            ticker = intent.getStringExtra(EXTRA_TICKER).orEmpty(),
            price = intent.getStringExtra(EXTRA_PRICE).orEmpty(),
            summary = intent.getStringExtra(EXTRA_SUMMARY).orEmpty(),
            risk = intent.getIntExtra(EXTRA_RISK, 0),
            parseError = intent.getBooleanExtra(EXTRA_PARSE_ERROR, false),
            inferenceMs = intent.getLongExtra(EXTRA_INFERENCE_MS, 0L)
        )
    }

    private fun renderResult(
        ticker: String,
        price: String,
        summary: String,
        risk: Int,
        parseError: Boolean,
        inferenceMs: Long
    ) {
        val card = ensureCard()
        card.findViewById<ProgressBar>(R.id.overlayProgress).visibility = View.GONE
        val title = listOf(ticker.ifBlank { "Analysis" }, price)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        card.findViewById<TextView>(R.id.overlayTitle).text = title
        val details = buildString {
            append(summary)
            if (risk > 0) append("\n\nRisk: ").append(risk).append(" / 10")
            append("\n\nOn-device · ").append(inferenceMs).append(" ms")
            if (parseError) append("\nParse error: showing raw model response.")
        }
        card.findViewById<TextView>(R.id.overlayDetails).text = details
        card.visibility = View.VISIBLE
        scheduleDismiss()
    }

    private fun ensureCard(): View {
        cardView?.let { return it }
        val view = layoutInflater.inflate(R.layout.overlay_card, null)
        val params = WindowManager.LayoutParams(
            dp(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(96)
        }
        view.findViewById<TextView>(R.id.overlayClose).setOnClickListener {
            view.visibility = View.GONE
        }
        windowManager.addView(view, params)
        cardView = view
        cardParams = params
        return view
    }

    private fun scheduleDismiss() {
        cardDismissRunnable?.let(mainHandler::removeCallbacks)
        val runnable = Runnable { cardView?.visibility = View.GONE }
        cardDismissRunnable = runnable
        mainHandler.postDelayed(runnable, CARD_DISMISS_MS)
    }

    private fun removeOverlays() {
        cardView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        cardView = null
        bubbleView = null
        cardParams = null
        bubbleParams = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            "TradeLens Local overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent status for the user-started analysis overlay."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun startForegroundWithType(type: Int) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun parcelableIntent(container: Intent, key: String): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            container.getParcelableExtra(key, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            container.getParcelableExtra(key)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_START = "com.tradelens.local.action.START_OVERLAY"
        const val ACTION_CAPTURE = "com.tradelens.local.action.CAPTURE"
        const val ACTION_LOADING = "com.tradelens.local.action.LOADING"
        const val ACTION_RESULT = "com.tradelens.local.action.RESULT"
        const val ACTION_ERROR = "com.tradelens.local.action.ERROR"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TICKER = "ticker"
        const val EXTRA_PRICE = "price"
        const val EXTRA_SUMMARY = "summary"
        const val EXTRA_RISK = "risk"
        const val EXTRA_PARSE_ERROR = "parse_error"
        const val EXTRA_INFERENCE_MS = "inference_ms"
        private const val MAX_SCREEN_TEXT_CHARS = 4_000
        private const val NOTIFICATION_CHANNEL = "tradelens_overlay"
        private const val NOTIFICATION_ID = 9021
        private const val CARD_DISMISS_MS = 20_000L
        private const val CAPTURE_FOREGROUND_SETTLE_MS = 700L
        private const val POSITION_PREFS = "overlay_position"
        private const val POSITION_X = "x"
        private const val POSITION_Y = "y"

        fun start(context: Context) {
            if (!Permissions.canDrawOverlays(context)) {
                Permissions.openOverlaySettings(context)
                return
            }
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun showLoading(context: Context) {
            startWithAction(context, ACTION_LOADING)
        }

        fun showResult(
            context: Context,
            ticker: String,
            price: String,
            summary: String,
            risk: Int,
            parseError: Boolean,
            inferenceMs: Long
        ) {
            val intent = Intent(context, OverlayService::class.java)
                .setAction(ACTION_RESULT)
                .putExtra(EXTRA_TICKER, ticker)
                .putExtra(EXTRA_PRICE, price)
                .putExtra(EXTRA_SUMMARY, summary)
                .putExtra(EXTRA_RISK, risk)
                .putExtra(EXTRA_PARSE_ERROR, parseError)
                .putExtra(EXTRA_INFERENCE_MS, inferenceMs)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startCapture(
            context: Context,
            resultCode: Int,
            data: Intent
        ) {
            val intent = Intent(context, OverlayService::class.java)
                .setAction(ACTION_CAPTURE)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        private fun startWithAction(context: Context, action: String) {
            val intent = Intent(context, OverlayService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
