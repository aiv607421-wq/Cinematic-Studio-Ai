package com.cinematicstudio.ai.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer

/**
 * Runs a real Media3 [Transformer] export with progress reporting and
 * cancellation. Progress is polled from the Transformer rather than simulated,
 * and the completion callback only fires once the file is finalized.
 */
class ExportRenderer(context: Context) {

    interface Callback {
        fun onProgress(percent: Int)
        fun onCompleted(outputPath: String, result: ExportResult)
        fun onError(message: String, cause: Throwable?)
        fun onCancelled()
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var transformer: Transformer? = null
    private var progressRunnable: Runnable? = null
    private var callback: Callback? = null

    val isRunning: Boolean get() = transformer != null

    /**
     * Renders [composition] to [outputPath]. The file is written directly by the
     * media pipeline; no partial file is reported as a finished export.
     */
    fun start(composition: Composition, outputPath: String, callback: Callback) {
        if (transformer != null) {
            callback.onError("An export is already running", null)
            return
        }
        this.callback = callback

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, result: ExportResult) {
                stopPolling()
                transformer = null
                val cb = callback
                this@ExportRenderer.callback = null
                cb?.onCompleted(outputPath, result)
            }

            override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                stopPolling()
                transformer = null
                val cb = callback
                this@ExportRenderer.callback = null
                cb?.onError(describe(exception), exception)
            }
        }

        val built = try {
            Transformer.Builder(appContext)
                .setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H264)
                .setAudioMimeType(androidx.media3.common.MimeTypes.AUDIO_AAC)
                .addListener(listener)
                .build()
        } catch (e: Exception) {
            this.callback = null
            callback.onError("Could not create the renderer: ${e.message}", e)
            return
        }

        transformer = built
        try {
            built.start(composition, outputPath)
        } catch (e: Exception) {
            stopPolling()
            transformer = null
            this.callback = null
            callback.onError("Export failed to start: ${e.message}", e)
            return
        }
        pollProgress()
    }

    fun cancel() {
        val running = transformer ?: return
        stopPolling()
        running.cancel()
        transformer = null
        val cb = callback
        callback = null
        cb?.onCancelled()
    }

    private fun pollProgress() {
        val holder = ProgressHolder()
        val runnable = object : Runnable {
            override fun run() {
                val current = transformer ?: return
                val state = try {
                    current.getProgress(holder)
                } catch (e: Exception) {
                    stopPolling()
                    return
                }
                when (state) {
                    Transformer.PROGRESS_STATE_AVAILABLE -> callback?.onProgress(holder.progress)
                    Transformer.PROGRESS_STATE_NOT_STARTED -> Unit
                    Transformer.PROGRESS_STATE_UNAVAILABLE, Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY -> Unit
                }
                if (transformer != null) mainHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
            }
        }
        progressRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopPolling() {
        progressRunnable?.let { mainHandler.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun describe(exception: ExportException): String {
        val cause = exception.cause
        return when {
            exception.errorCodeName.contains("RENDERING") -> "Rendering failed: ${cause?.message ?: exception.message}"
            exception.errorCodeName.contains("MUXING") -> "Writing the output file failed: ${cause?.message ?: exception.message}"
            exception.errorCodeName.contains("DECODING") -> "A source clip could not be decoded: ${cause?.message ?: exception.message}"
            exception.errorCodeName.contains("ENCODING") -> "Encoding failed, the device may not support this format: ${cause?.message ?: exception.message}"
            else -> "Export failed (${exception.errorCodeName}): ${cause?.message ?: exception.message}"
        }
    }

    companion object {
        private const val PROGRESS_INTERVAL_MS = 250L
    }
}
