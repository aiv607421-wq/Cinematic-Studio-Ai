package com.cinematicstudio.ai.ui.editor

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cinematicstudio.ai.data.model.TrackType
import com.cinematicstudio.ai.engine.ExportPlanner
import com.cinematicstudio.ai.viewmodel.EditorViewModel
import kotlinx.coroutines.delay

private enum class BottomTab(val label: String) {
    TIMELINE("Timeline"),
    MEDIA("Media"),
    INFO("Clip info"),
}

/** The editing workspace: preview monitor, media browser, multitrack timeline. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    projectId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: EditorViewModel = viewModel(
        key = projectId,
        factory = EditorViewModel.factory(context.applicationContext as Application, projectId),
    )

    val project by viewModel.project.collectAsState()
    val ui by viewModel.ui.collectAsState()
    val export by viewModel.export.collectAsState()
    var isPlaying by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(BottomTab.TIMELINE) }
    var showExport by remember { mutableStateOf(false) }
    var showAddTrack by remember { mutableStateOf(false) }

    val current = project
    val composition = remember(current) { viewModel.buildComposition() }

    // Clear transient status messages.
    LaunchedEffect(ui.message) {
        if (ui.message != null) {
            delay(3000)
            viewModel.setMessage(null)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(current?.name ?: "Editor", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                ui.loading -> "Loading…"
                                ui.error != null -> ui.error ?: ""
                                ui.recovered -> "Recovered from autosave"
                                else -> "${current?.settings?.width}×${current?.settings?.height} · " +
                                    "${current?.settings?.frameRate} fps"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.saveNow()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to projects")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::undo, enabled = ui.canUndo) {
                        Icon(Icons.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = viewModel::redo, enabled = ui.canRedo) {
                        Icon(Icons.Filled.Redo, contentDescription = "Redo")
                    }
                    IconButton(onClick = { viewModel.saveNow() }) {
                        Icon(Icons.Filled.Save, contentDescription = "Save project")
                    }
                    TextButton(onClick = { showExport = true }, enabled = current != null) {
                        Text("Export", color = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        when {
            ui.loading -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }

            ui.error != null -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(ui.error ?: "", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onBack) { Text("Back to projects") }
            }

            current != null -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                PreviewMonitor(
                    composition = composition,
                    playheadMs = ui.playheadMs,
                    isPlaying = isPlaying,
                    frameRate = current.settings.frameRate,
                    durationMs = current.timeline.durationMs,
                    onPlayheadChange = viewModel::setPlayhead,
                    onPlayingChange = { isPlaying = it },
                    onError = { viewModel.setMessage(it) },
                    modifier = Modifier.fillMaxWidth(),
                )

                // Panel switcher
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BottomTab.entries.forEach { entry ->
                        FilterChip(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            label = { Text(entry.label) },
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                when (tab) {
                    BottomTab.TIMELINE -> TimelineView(
                        project = current,
                        playheadMs = ui.playheadMs,
                        pixelsPerMs = ui.pixelsPerMs,
                        selectedClipId = ui.selectedClipId,
                        onZoomChange = viewModel::setZoom,
                        onPlayheadChange = viewModel::setPlayhead,
                        onSelectClip = viewModel::selectClip,
                        onMoveClip = { id, start, trackId -> viewModel.moveClip(id, start, trackId) },
                        onTrimClip = { id, start, end -> viewModel.trimClip(id, start, end) },
                        onToggleTrack = { id, muted, hidden, locked ->
                            viewModel.toggleTrack(id, muted, hidden, locked)
                        },
                        snap = { value, exclude -> viewModel.snapValue(value, SNAP_THRESHOLD_MS, exclude) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )

                    BottomTab.MEDIA -> MediaPanel(
                        assets = current.assets,
                        missingIds = current.missingAssetIds.toSet(),
                        onImport = viewModel::importMedia,
                        onAddToTimeline = { viewModel.addAssetToTimeline(it) },
                        onRelink = { id, uri -> viewModel.relinkAsset(id, uri) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                    )

                    BottomTab.INFO -> ClipInfoPanel(
                        selectedClipId = ui.selectedClipId,
                        project = current,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                    )
                }

                Spacer(Modifier.height(6.dp))

                // Editing toolbar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ToolButton(Icons.Filled.ContentCut, "Split at playhead", enabled = true) {
                        viewModel.splitAtPlayhead()
                    }
                    ToolButton(
                        Icons.Filled.Delete,
                        "Delete selected clip",
                        enabled = ui.selectedClipId != null,
                    ) { viewModel.deleteSelected(ripple = true) }
                    ToolButton(
                        Icons.Filled.ContentCopy,
                        "Duplicate selected clip",
                        enabled = ui.selectedClipId != null,
                    ) { viewModel.duplicateSelected() }
                    ToolButton(Icons.Filled.Add, "Add track") { showAddTrack = true }
                    ToolButton(Icons.Filled.VideoFile, "Import media") { tab = BottomTab.MEDIA }
                }

                ui.message?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }

    if (showAddTrack && current != null) {
        AddTrackDialog(
            onConfirm = { type ->
                viewModel.addTrack(type)
                showAddTrack = false
            },
            onDismiss = { showAddTrack = false },
        )
    }

    if (showExport && current != null) {
        val firstClipAsset = current.assets.firstOrNull { asset ->
            current.timeline.tracks.any { track -> track.clips.any { it.assetId == asset.id } }
        }
        ExportDialog(
            project = current,
            exportState = export,
            outputResolution = (firstClipAsset?.width?.takeIf { it > 0 } ?: current.settings.width) to
                (firstClipAsset?.height?.takeIf { it > 0 } ?: current.settings.height),
            gaps = ExportPlanner.describeGaps(current),
            onStart = { name -> viewModel.startExport(name) },
            onCancel = { viewModel.cancelExport() },
            onPublish = { path -> viewModel.publishToGallery(path) },
            onDismiss = {
                viewModel.clearExportState()
                showExport = false
            },
        )
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun AddTrackDialog(
    onConfirm: (TrackType) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add track") },
        text = {
            Column {
                TrackType.entries.forEach { type ->
                    TextButton(onClick = { onConfirm(type) }) { Text(type.name.lowercase().replaceFirstChar { it.uppercase() }) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ClipInfoPanel(
    selectedClipId: String?,
    project: com.cinematicstudio.ai.data.model.EditorProject,
    modifier: Modifier = Modifier,
) {
    val found = selectedClipId?.let { project.timeline.clip(it) }
    if (found == null) {
        Text(
            "Select a clip on the timeline to inspect its source, position and length.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    val (track, clip) = found
    val asset = project.asset(clip.assetId)
    Column(modifier) {
        Text(clip.label ?: asset?.displayName ?: "Clip", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        InfoLine("Track", track.name)
        InfoLine("Start", com.cinematicstudio.ai.engine.Timecode.formatTimecode(clip.startMs, project.settings.frameRate))
        InfoLine("End", com.cinematicstudio.ai.engine.Timecode.formatTimecode(clip.endMs, project.settings.frameRate))
        InfoLine("Duration", com.cinematicstudio.ai.engine.Timecode.formatTimecode(clip.durationMs, project.settings.frameRate))
        InfoLine(
            "Source in",
            com.cinematicstudio.ai.engine.Timecode.formatTimecode(clip.sourceInMs, project.settings.frameRate),
        )
        asset?.let {
            InfoLine("Source", "${it.width}×${it.height} · ${com.cinematicstudio.ai.engine.Timecode.formatDuration(it.durationMs)}")
        }
        InfoLine("Track state", buildString {
            if (track.muted) append("muted ")
            if (track.hidden) append("hidden ")
            if (track.locked) append("locked ")
            if (isBlank()) append("normal")
        })
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1.4f),
        )
    }
}

private const val SNAP_THRESHOLD_MS = 220L
