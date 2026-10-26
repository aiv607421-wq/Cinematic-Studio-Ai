package com.cinematicstudio.ai.ui.editor

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.transformer.Composition
import com.cinematicstudio.ai.engine.PreviewController
import com.cinematicstudio.ai.engine.Timecode
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Preview monitor: renders the timeline composition on a SurfaceView and shows
 * transport controls with a frame-accurate timecode.
 */
@Composable
fun PreviewMonitor(
    composition: Composition?,
    playheadMs: Long,
    isPlaying: Boolean,
    frameRate: Int,
    durationMs: Long,
    onPlayheadChange: (Long) -> Unit,
    onPlayingChange: (Boolean) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val frameDurationMs = 1_000L / frameRate.coerceAtLeast(1)
    val controller = remember { PreviewController(context) }
    var surfaceReady by remember { mutableStateOf(false) }
    var loop by remember { mutableStateOf(false) }
    val currentOnPosition by rememberUpdatedState(onPlayheadChange)
    val currentOnPlaying by rememberUpdatedState(onPlayingChange)

    DisposableEffect(Unit) {
        controller.onPlaybackEnded = { currentOnPlaying(false) }
        controller.onError = { message -> currentOnPlaying(false); onError(message) }
        onDispose {
            controller.onPlaybackEnded = null
            controller.onError = null
            controller.release()
        }
    }

    // Load the timeline state whenever the edit changes (or the surface appears).
    LaunchedEffect(composition, surfaceReady) {
        if (composition != null && surfaceReady) {
            controller.setComposition(composition, playheadMs, autoplay = isPlaying)
        }
    }

    // Apply play/pause from the toolbar to the player.
    LaunchedEffect(isPlaying) {
        if (isPlaying) controller.play() else controller.pause()
    }

    // Mirror the playhead while playing.
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isActive) {
                delay(50)
                currentOnPosition(controller.currentPositionMs)
            }
        }
    }

    // Scrub: jump the player when the playhead moves without playback.
    LaunchedEffect(playheadMs) {
        if (!isPlaying && surfaceReady) controller.seekTo(playheadMs)
    }

    LaunchedEffect(loop) { controller.setLooping(loop) }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .aspectRatio(16f / 9f),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                surfaceReady = true
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                holder.surface?.let { controller.attachSurface(it, width, height) }
                                surfaceReady = true
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                surfaceReady = false
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (composition == null) {
                Text(
                    "Add a clip to the timeline to start previewing",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = {
                controller.seekTo(0)
                currentOnPosition(0)
            }) {
                Icon(Icons.Filled.FirstPage, contentDescription = "Go to start", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = {
                val target = (playheadMs - frameDurationMs).coerceAtLeast(0L)
                controller.seekTo(target)
                currentOnPosition(target)
            }) {
                Icon(
                    imageVector = Icons.Filled.ChevronLeft,
                    contentDescription = "Step back one frame",
                    modifier = Modifier.size(22.dp),
                )
            }
            IconButton(onClick = { currentOnPlaying(!isPlaying) }) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = {
                val target = playheadMs + frameDurationMs
                controller.seekTo(target)
                currentOnPosition(target)
            }) {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = "Step forward one frame",
                    modifier = Modifier.size(22.dp),
                )
            }
            IconButton(onClick = { loop = !loop }) {
                Icon(
                    imageVector = Icons.Filled.Repeat,
                    contentDescription = if (loop) "Loop on" else "Loop off",
                    modifier = Modifier.size(18.dp),
                    tint = if (loop) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    Timecode.formatTimecode(playheadMs, frameRate),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "/ ${Timecode.formatTimecode(durationMs, frameRate)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
