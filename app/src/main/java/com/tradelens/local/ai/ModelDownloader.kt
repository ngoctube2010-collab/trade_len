package com.tradelens.local.ai

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val fraction: Float?
)

object ModelDownloader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun download(url: String, destination: File): Flow<DownloadProgress> = callbackFlow {
        if (url.isBlank()) {
            close(IllegalArgumentException("Enter a model download URL."))
            return@callbackFlow
        }
        val parent = destination.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            close(IOException("Could not create the app model directory."))
            return@callbackFlow
        }

        val partialFile = File(destination.parentFile, "${destination.name}.part")
        val fingerprintFile = File(destination.parentFile, "${destination.name}.part.urlhash")
        val urlFingerprint = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        val previousFingerprint = runCatching { fingerprintFile.readText() }.getOrNull()
        if (partialFile.exists() && previousFingerprint != urlFingerprint) {
            partialFile.delete()
        }
        try {
            fingerprintFile.writeText(urlFingerprint)
        } catch (error: IOException) {
            close(IOException("Could not prepare resumable model download metadata.", error))
            return@callbackFlow
        }
        val existingBytes = partialFile.takeIf { it.exists() }?.length() ?: 0L
        val requestBuilder = Request.Builder().url(url).get()
        if (existingBytes > 0L) requestBuilder.header("Range", "bytes=$existingBytes-")
        val call = client.newCall(requestBuilder.build())
        val producer = this

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (!call.isCanceled()) {
                    partialFile.delete()
                    fingerprintFile.delete()
                }
                close(error)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { result ->
                    try {
                        if (!result.isSuccessful) {
                            partialFile.delete()
                            throw IOException("Model server returned HTTP ${result.code}.")
                        }
                        val body = result.body ?: throw IOException("Model server returned no file.")
                        val append = result.code == 206 && existingBytes > 0L
                        if (append) {
                            val range = result.header("Content-Range").orEmpty()
                            if (!range.startsWith("bytes $existingBytes-")) {
                                partialFile.delete()
                                throw IOException("The server returned an unexpected resume range.")
                            }
                        }
                        val startingBytes = if (append) existingBytes else 0L
                        if (!append && existingBytes > 0L) partialFile.delete()
                        val remainingLength = body.contentLength()
                        if (remainingLength <= 0L) {
                            partialFile.delete()
                            throw IOException("The model server did not provide a valid Content-Length.")
                        }
                        val expectedTotal = startingBytes + remainingLength
                        partialFile.copyFrom(
                            input = body.byteStream(),
                            append = append,
                            startingBytes = startingBytes,
                            expectedTotal = expectedTotal,
                            progressScope = producer
                        )
                        if (partialFile.length() != expectedTotal) {
                            partialFile.delete()
                            throw IOException("Downloaded model size does not match Content-Length.")
                        }
                        moveIntoPlace(partialFile, destination)
                        fingerprintFile.delete()
                        trySend(
                            DownloadProgress(
                                downloadedBytes = expectedTotal,
                                totalBytes = expectedTotal,
                                fraction = 1f
                            )
                        )
                        close()
                    } catch (error: Throwable) {
                        if (producer.isActive) {
                            partialFile.delete()
                            fingerprintFile.delete()
                        }
                        close(error)
                    }
                }
            }
        })

        trySend(
            DownloadProgress(
                downloadedBytes = existingBytes,
                totalBytes = null,
                fraction = null
            )
        )
        awaitClose {
            call.cancel()
        }
    }.buffer(Channel.CONFLATED)

    fun delete(destination: File) {
        destination.delete()
        File(destination.parentFile, "${destination.name}.part").delete()
        File(destination.parentFile, "${destination.name}.part.urlhash").delete()
    }

    private fun moveIntoPlace(partialFile: File, destination: File) {
        try {
            java.nio.file.Files.move(
                partialFile.toPath(),
                destination.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            java.nio.file.Files.move(
                partialFile.toPath(),
                destination.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun File.copyFrom(
        input: java.io.InputStream,
        append: Boolean,
        startingBytes: Long,
        expectedTotal: Long,
        progressScope: ProducerScope<DownloadProgress>
    ) {
        input.use { source ->
            FileOutputStream(this, append).buffered().use { sink ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var downloaded = startingBytes
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    downloaded += read
                    progressScope.trySend(
                        DownloadProgress(
                            downloadedBytes = downloaded,
                            totalBytes = expectedTotal,
                            fraction = (downloaded.toFloat() / expectedTotal.toFloat())
                                .coerceIn(0f, 1f)
                        )
                    )
                }
            }
        }
    }
}
