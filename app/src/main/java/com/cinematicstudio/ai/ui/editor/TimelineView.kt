package com.cinematicstudio.ai.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cinematicstudio.ai.data.model.Clip
import com.cinematicstudio.ai.data.model.EditorProject
import com.cinematicstudio.ai.data.model.Track
import com.cinematicstudio.ai.data.model.TrackType
import com.cinematicstudio.ai.engine.TimelineOps
import com.cinematicstudio.ai.engine.Timecode
import com.cinematicstudio.ai.ui.theme.StudioColors
import kotlin.math.roundToInt

private enum class DragKind { MOVE, TRIM_START, TRIM_END }

private data class DragState(
    val clipId: String,
    val kind: DragKind,
    val originalStartMs: Long,
    val originalEndMs: Long,
    val currentStartMs: Long,
    val currentEndMs: Long,
    val targetTrackId: String,
)

/**
 * Multitrack timeline canvas.
 *
 * Interaction model (all discoverable, no hidden gestures):
 *  - tap a clip to select, tap empty space to move the playhead,
 *  - long-press + drag a clip to move it (snapping to clip edges and playhead;
 *    vertical dragging re-targets another track of the same kind),
 *  - drag the bright edges of a clip to trim,
 *  - pinch to zoom, drag the ruler to scrub.
 */
@Composable
fun TimelineView(
    project: EditorProject,
    playheadMs: Long,
    pixelsPerMs: Float,
    selectedClipId: String?,
    onZoomChange: (Float) -> Unit,
    onPlayheadChange: (Long) -> Unit,
    onSelectClip: (String?) -> Unit,
    onMoveClip: (String, Long, String?) -> Unit,
    onTrimClip: (String, Long, Long) -> Unit,
    onToggleTrack: (String, Boolean?, Boolean?, Boolean?) -> Unit,
    snap: (Long, String?) -> Long,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val dpPerMs = pixelsPerMs
    var dragState by remember { mutableStateOf<DragState?>(null) }

    val tracks = project.timeline.tracks
    val contentDurationMs = maxOf(project.timeline.durationMs, MIN_VISIBLE_MS)
    val contentWidthDp = contentDurationMs * dpPerMs + 480f
    val rulerHeight = 26
    val laneHeights = tracks.associateWith { trackHeightDp(it) }
    val totalHeight = rulerHeight + tracks.sumOf { laneHeights.getValue(it) }

    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(totalHeight.dp)
                .pointerInput(tracks.size, dpPerMs) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f) onZoomChange(dpPerMs * zoom)
                        if (pan.x != 0f) scrollState.dispatchRawDelta(-pan.x)
                    }
                },
        ) {
            Column(
                modifier = Modifier
                    .horizontalScroll(scrollState)
                    .fillMaxHeight()
                    .width(contentWidthDp.dp)
                    .pointerInput(tracks.size, dpPerMs) {
                        detectTapGestures { position ->
                            val timeMs = (position.x / dpPerMs).toLong()
                            if (timeMs >= 0) {
                                onSelectClip(null)
                                onPlayheadChange(timeMs)
                            }
                        }
                    },
            ) {
                Ruler(
                    durationMs = contentDurationMs,
                    dpPerMs = dpPerMs,
                    heightDp = rulerHeight,
                    frameRate = project.settings.frameRate,
                    onScrub = onPlayheadChange,
                    snapTime = { ms -> snap(ms, null) },
                )

                tracks.forEach { track ->
                    TrackLane(
                        track = track,
                        project = project,
                        dpPerMs = dpPerMs,
                        selectedClipId = selectedClipId,
                        dragState = dragState,
                        heightDp = laneHeights.getValue(track),
                        onSelect = onSelectClip,
                        onDragState = { dragState = it },
                        onCommitMove = onMoveClip,
                        onCommitTrim = onTrimClip,
                        snap = snap,
                    )
                }
            }

            // Playhead overlay pinned to the viewport.
            Box(
                modifier = Modifier
                    .offset { IntOffset(((playheadMs * dpPerMs) - scrollState.value).roundToInt(), 0) }
                    .width(2.dp)
                    .height(totalHeight.dp)
                    .background(StudioColors.Playhead),
            ) {
                Box(
                    Modifier
                        .offset(y = 4.dp)
                        .align(Alignment.TopCenter)
                        .width(10.dp)
                        .height(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(StudioColors.Playhead),
                )
            }

            if (tracks.all { it.clips.isEmpty() }) {
                Text(
                    "Import media, then tap it to place it on the timeline",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(start = 110.dp, end = 8.dp),
                )
            }
        }

        // Track headers sit outside the horizontal scroll so they stay visible.
        Column(
            modifier = Modifier
                .width(96.dp)
                .height(totalHeight.dp)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Box(
                Modifier
                    .height(rulerHeight.dp)
                    .fillMaxWidth()
                    .background(StudioColors.SurfaceHigh)
            )
            tracks.forEach { track ->
                TrackHeader(
                    track = track,
                    heightDp = laneHeights.getValue(track),
                    onToggle = onToggleTrack,
                )
            }
        }
    }
}

private fun trackHeightDp(track: Track): Int = if (track.type == TrackType.VIDEO) 74 else 58

@Composable
private fun Ruler(
    durationMs: Long,
    dpPerMs: Float,
    heightDp: Int,
    frameRate: Int,
    onScrub: (Long) -> Unit,
    snapTime: (Long) -> Long,
) {
    val stepMs = timeStepFor(dpPerMs)
    val ticks = buildList {
        var t = 0L
        while (t <= durationMs) {
            add(t)
            t += stepMs
        }
    }
    Box(
        modifier = Modifier
            .height(heightDp.dp)
            .fillMaxWidth()
            .background(StudioColors.SurfaceHigh)
            .pointerInput(dpPerMs, durationMs) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val raw = (change.position.x / dpPerMs).toLong().coerceAtLeast(0L)
                    onScrub(snapTime(raw))
                }
            },
    ) {
        val labelEvery = if (stepMs * dpPerMs > 90f) stepMs else stepMs * 5
        ticks.forEach { tick ->
            val major = tick % labelEvery == 0L
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset { IntOffset((tick * dpPerMs).roundToInt(), 0) }
                    .width(1.dp)
                    .height(if (tick % (stepMs * 5) == 0L) 14.dp else 8.dp)
                    .background(StudioColors.Outline),
            )
            if (major) {
                Text(
                    Timecode.formatDuration(tick),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset { IntOffset((tick * dpPerMs + 3).roundToInt(), -1 ) },
                )
            }
        }
    }
}

@Composable
private fun TrackLane(
    track: Track,
    project: EditorProject,
    dpPerMs: Float,
    selectedClipId: String?,
    dragState: DragState?,
    heightDp: Int,
    onSelect: (String?) -> Unit,
    onDragState: (DragState?) -> Unit,
    onCommitMove: (String, Long, String?) -> Unit,
    onCommitTrim: (String, Long, Long) -> Unit,
    snap: (Long, String?) -> Long,
) {
    val laneColor = when (track.type) {
        TrackType.VIDEO -> StudioColors.TrackVideo
        TrackType.AUDIO -> StudioColors.TrackAudio
        TrackType.TEXT -> StudioColors.TrackText
    }
    val borderColor = when (track.type) {
        TrackType.VIDEO -> StudioColors.TrackVideoBorder
        TrackType.AUDIO -> StudioColors.TrackAudioBorder
        TrackType.TEXT -> StudioColors.TrackTextBorder
    }
    val sameTypeTrackIds = project.timeline.tracks.filter { it.type == track.type }.map { it.id }

    Box(
        modifier = Modifier
            .height(heightDp.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f)),
    ) {
        track.sortedClips.forEach { clip ->
            ClipCell(
                clip = clip,
                project = project,
                dpPerMs = dpPerMs,
                selected = clip.id == selectedClipId,
                dragState = dragState,
                laneColor = laneColor,
                borderColor = borderColor,
                dimmed = track.hidden,
                onSelect = onSelect,
                onDragState = onDragState,
                onCommitMove = onCommitMove,
                onCommitTrim = onCommitTrim,
                snap = snap,
                laneHeightDp = heightDp,
                sameTypeTrackIds = sameTypeTrackIds,
                currentTrackId = track.id,
            )
        }
    }
}

@Composable
private fun ClipCell(
    clip: Clip,
    project: EditorProject,
    dpPerMs: Float,
    selected: Boolean,
    dragState: DragState?,
    laneColor: Color,
    borderColor: Color,
    dimmed: Boolean,
    onSelect: (String?) -> Unit,
    onDragState: (DragState?) -> Unit,
    onCommitMove: (String, Long, String?) -> Unit,
    onCommitTrim: (String, Long, Long) -> Unit,
    snap: (Long, String?) -> Long,
    laneHeightDp: Int,
    sameTypeTrackIds: List<String>,
    currentTrackId: String,
) {
    // Gesture callbacks live in pointerInput lambdas that outlive composition,
    // so they read the latest drag state through this indirection.
    val currentDrag by rememberUpdatedState(dragState)
    val currentDpPerMs by rememberUpdatedState(dpPerMs)

    val active = dragState?.takeIf { it.clipId == clip.id }
    val startMs = active?.currentStartMs ?: clip.startMs
    val endMs = active?.currentEndMs ?: clip.endMs
    val widthDp = ((endMs - startMs) * dpPerMs).coerceAtLeast(4f)
    val asset = project.asset(clip.assetId)
    val baseClipStart = clip.startMs
    val baseClipEnd = clip.endMs

    Box(
        modifier = Modifier
            .offset { IntOffset((startMs * dpPerMs).roundToInt(), 3) }
            .width(widthDp.dp)
            .requiredHeight((laneHeightDp - 6).dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) borderColor else laneColor)
            .then(if (dimmed) Modifier.background(Color.Black.copy(alpha = 0.45f)) else Modifier)
            .pointerInput(clip.id) {
                var accumulatedDy = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        accumulatedDy = 0f
                        onSelect(clip.id)
                        onDragState(
                            DragState(
                                clipId = clip.id,
                                kind = DragKind.MOVE,
                                originalStartMs = baseClipStart,
                                originalEndMs = baseClipEnd,
                                currentStartMs = baseClipStart,
                                currentEndMs = baseClipEnd,
                                targetTrackId = currentTrackId,
                            )
                        )
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val state = currentDrag ?: return@detectDragGesturesAfterLongPress
                        val perMs = currentDpPerMs
                        val deltaMs = (dragAmount.x / perMs).toLong()
                        accumulatedDy += dragAmount.y
                        val laneHeightPx = with(density) { laneHeightDp.dp.toPx() }
                        val currentIndex = sameTypeTrackIds.indexOf(currentTrackId).coerceAtLeast(0)
                        val targetIndex = (currentIndex + (accumulatedDy / laneHeightPx).roundToInt())
                            .coerceIn(0, sameTypeTrackIds.lastIndex.coerceAtLeast(0))
                        val raw = (state.currentStartMs + deltaMs).coerceAtLeast(0L)
                        val snapped = snap(raw, clip.id)
                        val length = state.originalEndMs - state.originalStartMs
                        onDragState(
                            state.copy(
                                currentStartMs = snapped,
                                currentEndMs = snapped + length,
                                targetTrackId = sameTypeTrackIds.getOrElse(targetIndex) { currentTrackId },
                            )
                        )
                    },
                    onDragCancel = { onDragState(null) },
                    onDragEnd = {
                        val state = currentDrag
                        onDragState(null)
                        if (state != null && state.kind == DragKind.MOVE) {
                            onCommitMove(clip.id, state.currentStartMs, state.targetTrackId)
                        }
                    },
                )
            }
            .pointerInput(clip.id) {
                detectTapGestures(onTap = { onSelect(clip.id) })
            },
    ) {
        if (selected) {
            TrimHandle(
                modifier = Modifier.align(Alignment.CenterStart),
                onDrag = { deltaPx ->
                    val state = currentDrag?.takeIf { it.clipId == clip.id }
                    val baseStart = state?.originalStartMs ?: baseClipStart
                    val baseEnd = state?.originalEndMs ?: baseClipEnd
                    val deltaMs = (deltaPx / currentDpPerMs).toLong()
                    val candidate = (baseStart + deltaMs).coerceAtMost(baseEnd - TimelineOps.MIN_CLIP_MS)
                    val snapped = snap(candidate, clip.id)
                    onDragState(
                        DragState(
                            clipId = clip.id,
                            kind = DragKind.TRIM_START,
                            originalStartMs = baseStart,
                            originalEndMs = baseEnd,
                            currentStartMs = snapped,
                            currentEndMs = baseEnd,
                            targetTrackId = currentTrackId,
                        )
                    )
                },
                onCommit = {
                    val state = currentDrag?.takeIf { it.clipId == clip.id }
                    onDragState(null)
                    if (state != null) onCommitTrim(clip.id, state.currentStartMs, state.currentEndMs)
                },
            )
            TrimHandle(
                modifier = Modifier.align(Alignment.CenterEnd),
                onDrag = { deltaPx ->
                    val state = currentDrag?.takeIf { it.clipId == clip.id }
                    val baseStart = state?.originalStartMs ?: baseClipStart
                    val baseEnd = state?.originalEndMs ?: baseClipEnd
                    val deltaMs = (deltaPx / currentDpPerMs).toLong()
                    val candidate = (baseEnd + deltaMs).coerceAtLeast(baseStart + TimelineOps.MIN_CLIP_MS)
                    val snapped = snap(candidate, clip.id)
                    onDragState(
                        DragState(
                            clipId = clip.id,
                            kind = DragKind.TRIM_END,
                            originalStartMs = baseStart,
                            originalEndMs = baseEnd,
                            currentStartMs = baseStart,
                            currentEndMs = snapped,
                            targetTrackId = currentTrackId,
                        )
                    )
                },
                onCommit = {
                    val state = currentDrag?.takeIf { it.clipId == clip.id }
                    onDragState(null)
                    if (state != null) onCommitTrim(clip.id, state.currentStartMs, state.currentEndMs)
                },
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = if (selected) 20.dp else 8.dp, end = if (selected) 20.dp else 8.dp),
        ) {
            Text(
                clip.label ?: asset?.displayName ?: "Clip",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                Timecode.formatDuration(clip.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.72f),
            )
        }

        if (active != null && active.kind == DragKind.MOVE) {
            Text(
                Timecode.formatTimecode(active.currentStartMs, project.settings.frameRate),
                style = MaterialTheme.typography.labelSmall,
                color = StudioColors.Accent,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp, top = 3.dp),
            )
        }
    }
}

@Composable
private fun TrimHandle(
    modifier: Modifier = Modifier,
    onDrag: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(18.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x)
                    },
                    onDragEnd = { onCommit() },
                    onDragCancel = { onCommit() },
                )
            },
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .width(4.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(StudioColors.Accent),
        )
    }
}

@Composable
private fun TrackHeader(
    track: Track,
    heightDp: Int,
    onToggle: (String, Boolean?, Boolean?, Boolean?) -> Unit,
) {
    Row(
        modifier = Modifier
            .height(heightDp.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 6.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                track.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${track.clips.size} clip${if (track.clips.size == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.type != TrackType.TEXT) {
                    HeaderToggle(
                        icon = if (track.muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        contentDescription = if (track.muted) "Unmute track" else "Mute track",
                        active = track.muted,
                        onClick = { onToggle(track.id, !track.muted, null, null) },
                    )
                }
                HeaderToggle(
                    icon = if (track.hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (track.hidden) "Show track" else "Hide track",
                    active = track.hidden,
                    onClick = { onToggle(track.id, null, !track.hidden, null) },
                )
                HeaderToggle(
                    icon = if (track.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = if (track.locked) "Unlock track" else "Lock track",
                    active = track.locked,
                    onClick = { onToggle(track.id, null, null, !track.locked) },
                )
            }
        }
    }
}

@Composable
private fun HeaderToggle(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.height(30.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.height(16.dp),
            tint = if (active) StudioColors.Accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Chooses a ruler step that keeps labels readable at the current zoom. */
internal fun timeStepFor(dpPerMs: Float): Long {
    val candidates = longArrayOf(100, 250, 500, 1000, 2000, 5000, 10_000, 30_000, 60_000, 120_000, 300_000)
    for (candidate in candidates) {
        if (candidate * dpPerMs >= 90f) return candidate
    }
    return candidates.last()
}

private const val MIN_VISIBLE_MS = 10_000L
