package com.cinematicstudio.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TimecodeTest {

    @Test
    fun timecodeUsesHoursMinutesSecondsFrames() {
        assertEquals("00:00:00:00", Timecode.formatTimecode(0, 30))
        assertEquals("00:00:01:00", Timecode.formatTimecode(1_000, 30))
        assertEquals("00:00:01:15", Timecode.formatTimecode(1_500, 30))
        assertEquals("01:01:01:12", Timecode.formatTimecode(3_661_500, 25))
    }

    @Test
    fun timecodeIsClampedAtZeroForNegativeInput() {
        assertEquals("00:00:00:00", Timecode.formatTimecode(-500, 24))
    }

    @Test
    fun frameCountMatchesTheFrameRate() {
        // 2 seconds at 24 fps == frame 48.
        assertEquals(48L, Timecode.toFrame(2_000, 24))
        assertEquals(2_000L, Timecode.fromFrame(48, 24))
    }

    @Test
    fun durationLabelsStayShort() {
        assertEquals("12s", Timecode.formatDuration(12_000))
        assertEquals("1:04", Timecode.formatDuration(64_000))
        assertEquals("0s", Timecode.formatDuration(0))
    }

    @Test
    fun relativeTimeIsHumanReadable() {
        val now = 1_700_000_000_000L
        assertEquals("just now", Timecode.formatRelativeTime(now - 5_000, now))
        assertEquals("12m ago", Timecode.formatRelativeTime(now - 12 * 60_000, now))
        assertEquals("3h ago", Timecode.formatRelativeTime(now - 3 * 3_600_000, now))
        assertEquals("2d ago", Timecode.formatRelativeTime(now - 2 * 86_400_000, now))
    }
}
