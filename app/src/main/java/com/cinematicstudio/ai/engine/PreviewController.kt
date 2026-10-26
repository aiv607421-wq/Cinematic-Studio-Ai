package com.cinematicstudio.ai.engine

import android.content.Context
import android.view.Surface
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer

/**
 * Thin wrapper around Media3's [CompositionPlayer].
 *
 * Preview and export consume the same [Composition], so timeline gaps, clip
 * trims and track order behave identically while editing and after rendering.
 */
class PreviewController(context: Context) {

    val player: CompositionPlayer = CompositionPlayer.Builder(context.applicationContext).build()

    var onPlaybackEnded: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) onPlaybackEnded?.invoke()
        }

        override fun onPlayerError(error: PlaybackException) {
            onError?.invoke(error.message ?: "Playback failed")
        }
    }

    init {
        player.addListener(listener)
    }

    val isPlaying: Boolean get() = player.isPlaying
    val currentPositionMs: Long get() = player.currentPosition.coerceAtLeast(0L)
    val durationMs: Long get() = player.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0L

    fun attachSurface(surface: Surface, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        player.setVideoSurface(surface, androidx.media3.common.util.Size(width, height))
    }

    /** Loads a new timeline state, preserving the current playhead position. */
    fun setComposition(composition: Composition?, startAtMs: Long, autoplay: Boolean) {
        if (composition == null) {
            runCatching { player.pause() }
            return
        }
        player.setComposition(composition, startAtMs.coerceAtLeast(0L))
        player.prepare()
        if (autoplay) player.play() else player.pause()
    }

    fun play() = player.play()

    fun pause() = player.pause()

    fun seekTo(ms: Long) = player.seekTo(ms.coerceAtLeast(0L))

    fun setLooping(enabled: Boolean) {
        player.repeatMode = if (enabled) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    }

    fun release() {
        runCatching { player.release() }
        onPlaybackEnded = null
        onError = null
    }
}
