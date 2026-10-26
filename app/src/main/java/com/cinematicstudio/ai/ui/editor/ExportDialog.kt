package com.cinematicstudio.ai.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cinematicstudio.ai.data.model.EditorProject
import com.cinematicstudio.ai.engine.ExportPlanner
import com.cinematicstudio.ai.engine.Timecode
import com.cinematicstudio.ai.viewmodel.ExportUiState

/**
 * Export panel.
 *
 * Only controls that the render pipeline actually honours are shown: Media3's
 * Transformer renders at the source clip resolution with H.264/AAC into MP4, so
 * resolution, codec and container are displayed as facts rather than offered as
 * (fake) settings.
 */
@Composable
fun ExportDialog(
    project: EditorProject,
    exportState: ExportUiState,
    outputResolution: Pair<Int, Int>,
    gaps: List<ExportPlanner.GapInfo>,
    onStart: (String) -> Unit,
    onCancel: () -> Unit,
    onPublish: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var fileName by remember { mutableStateOf(project.name) }

    AlertDialog(
        onDismissRequest = { if (!exportState.running) onDismiss() },
        title = { Text(if (exportState.outputPath != null) "Export complete" else "Export") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    exportState.running -> {
                        Text("Rendering your edit…", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { exportState.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${exportState.percent}% — this is real render progress, not a timer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    exportState.outputPath != null -> {
                        Text(
                            "The rendered file contains your trims, clip order and gaps.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        InfoRow("Saved to", exportState.outputPath ?: "")
                        if (exportState.publishedToGallery) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Copied to your gallery under “Cinematic Studio AI”.",
                                color = MaterialTheme.colorScheme.secondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    exportState.error != null -> {
                        Text(
                            exportState.error ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    exportState.cancelled -> {
                        Text("Export cancelled. No partial file is reported as finished.")
                    }

                    else -> {
                        OutlinedTextField(
                            value = fileName,
                            onValueChange = { fileName = it },
                            label = { Text("File name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        InfoRow("Duration", Timecode.formatTimecode(project.timeline.durationMs, project.settings.frameRate))
                        InfoRow("Resolution", "${outputResolution.first}×${outputResolution.second} (source clips)")
                        InfoRow("Frame rate", "${project.settings.frameRate} fps")
                        InfoRow("Container / codec", "MP4 · H.264 video · AAC audio")
                        InfoRow("Location", "Movies/Cinematic Studio AI (app folder)")
                        if (gaps.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "${gaps.size} empty region${if (gaps.size == 1) "" else "s"} will be rendered " +
                                    "as black/silence so timing stays exact.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (project.missingAssetIds.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Missing media must be relinked before exporting.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                exportState.running -> TextButton(onClick = onCancel) { Text("Cancel export") }
                exportState.outputPath != null -> Row {
                    if (!exportState.publishedToGallery) {
                        TextButton(onClick = { exportState.outputPath?.let(onPublish) }) { Text("Save to gallery") }
                    }
                    Button(onClick = onDismiss) { Text("Done") }
                }
                else -> Button(
                    onClick = { onStart(fileName) },
                    enabled = project.missingAssetIds.isEmpty() && project.timeline.durationMs > 0,
                ) { Text("Start export") }
            }
        },
        dismissButton = {
            if (!exportState.running && exportState.outputPath == null) {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
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
            modifier = Modifier
                .weight(1.4f)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}
