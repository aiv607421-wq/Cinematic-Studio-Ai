package com.cinematicstudio.ai.engine

import kotlin.math.absoluteValue

/** Timecode and duration formatting helpers used by the transport and timeline. */
object Timecode {

    /** Formats milliseconds as HH:MM:SS:FF at [frameRate] (drop-frame free, wall-clock frames). */
    fun formatTimecode(millis: Long, frameRate: Int): String {
        val safeFps = frameRate.coerceIn(1, 240)
        val total = millis.coerceAtLeast(0)
        val hours = total / 3_600_000
        val minutes = (total % 3_600_000) / 60_000
        val seconds = (total % 60_000) / 1000
        val frames = ((total % 1000) * safeFps) / 1000
        return "%02d:%02d:%02d:%02d".format(hours, minutes, seconds, frames)
    }

    /** Short duration label such as "1:04" / "12.3s" for project cards and clips. */
    fun formatDuration(millis: Long): String {
        val totalSeconds = (millis.coerceAtLeast(0)) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "%d:%02d".format(minutes, seconds) else "%ds".format(seconds)
    }

    /** Human label for a project's last-edited timestamp on the home screen. */
    fun formatRelativeTime(timestampMs: Long, nowMs: Long): String {
        if (timestampMs <= 0L) return "just now"
        val delta = (nowMs - timestampMs).absoluteValue
        val minutes = delta / 60_000
        val hours = delta / 3_600_000
        val days = delta / 86_400_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days < 30 -> "${days}d ago"
            else -> "${days / 30}mo ago"
        }
    }

    /** Converts a timeline position to whole frames for frame stepping. */
    fun toFrame(millis: Long, frameRate: Int): Long = (millis.coerceAtLeast(0) * frameRate) / 1000

    /** Converts a frame index back to milliseconds. */
    fun fromFrame(frame: Long, frameRate: Int): Long = (frame.coerceAtLeast(0) * 1000) / frameRate
}
