package com.tradelens.local.ocr

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ScreenCaptureManager {
    suspend fun captureAndRecognize(
        context: Context,
        resultCode: Int,
        resultData: Intent
    ): String = withTimeout(CAPTURE_TIMEOUT_MS) {
        val bitmap = withContext(Dispatchers.Main.immediate) {
            captureOneFrame(context.applicationContext, resultCode, resultData)
        }
        try {
            recognize(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun captureOneFrame(
        context: Context,
        resultCode: Int,
        resultData: Intent
    ): Bitmap = suspendCancellableCoroutine { continuation ->
        val metrics = DisplayMetrics()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val densityDpi = metrics.densityDpi
        val projectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            projectionManager.getMediaProjection(resultCode, resultData)
        } catch (error: Throwable) {
            continuation.resumeWithException(error)
            return@suspendCancellableCoroutine
        }
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val thread = HandlerThread("TradeLensScreenCapture").apply { start() }
        val handler = Handler(thread.looper)
        var virtualDisplay: VirtualDisplay? = null
        var projectionCallback: MediaProjection.Callback? = null
        val finished = AtomicBoolean(false)

        fun cleanup() {
            if (!finished.compareAndSet(false, true)) return
            runCatching { reader.setOnImageAvailableListener(null, null) }
            runCatching { virtualDisplay?.release() }
            projectionCallback?.let { callback ->
                runCatching { projection.unregisterCallback(callback) }
            }
            runCatching { projection.stop() }
            runCatching { reader.close() }
            runCatching { thread.quitSafely() }
        }

        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (!finished.get() && continuation.isActive) {
                    continuation.resumeWithException(
                        IllegalStateException("Screen capture permission ended before a frame arrived.")
                    )
                }
                cleanup()
            }
        }

        reader.setOnImageAvailableListener({ imageReader ->
            if (!continuation.isActive || finished.get()) return@setOnImageAvailableListener
            val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val bitmap = try {
                image.toBitmap()
            } catch (error: Throwable) {
                image.close()
                cleanup()
                if (continuation.isActive) continuation.resumeWithException(error)
                return@setOnImageAvailableListener
            }
            image.close()
            cleanup()
            if (continuation.isActive) continuation.resume(bitmap)
            else bitmap.recycle()
        }, handler)

        try {
            projection.registerCallback(projectionCallback!!, handler)
            virtualDisplay = projection.createVirtualDisplay(
                "TradeLensManualScreenCapture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
        } catch (error: Throwable) {
            cleanup()
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        continuation.invokeOnCancellation { cleanup() }
    }

    private suspend fun recognize(bitmap: Bitmap): String =
        suspendCancellableCoroutine { continuation ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val task = recognizer.process(InputImage.fromBitmap(bitmap, 0))
            task.addOnSuccessListener { result ->
                recognizer.close()
                if (continuation.isActive) continuation.resume(result.text.take(4_000))
            }
            task.addOnFailureListener { error ->
                recognizer.close()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            continuation.invokeOnCancellation { recognizer.close() }
        }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes.first()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedWidth = rowStride / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        val buffer = plane.buffer
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    private const val CAPTURE_TIMEOUT_MS = 30_000L
}
