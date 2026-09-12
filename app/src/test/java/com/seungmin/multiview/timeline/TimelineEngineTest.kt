package com.seungmin.multiview.timeline

import com.seungmin.multiview.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineEngineTest {
    @Test
    fun playbackSpeedChangesRealDuration() {
        val fast = tile(duration = 600_000L).copy(playbackSpeed = 2f)
        val slow = tile(duration = 600_000L).copy(playbackSpeed = .5f)
        assertEquals(300_000L, TimelineEngine.cycleDurationMs(fast))
        assertEquals(1_200_000L, TimelineEngine.cycleDurationMs(slow))
    }

    @Test
    fun reorderedHighlightsAndPerSegmentRepeatsBecomeVirtualSequence() {
        val a = HighlightSegment(id = "a", startMs = 0, endMs = 1_000, order = 1, repeatCount = 2)
        val b = HighlightSegment(id = "b", startMs = 5_000, endMs = 7_000, order = 0, repeatCount = 1)
        val tile = tile().copy(
            repeatMode = RepeatMode.HIGHLIGHT,
            highlightPlayMode = HighlightPlayMode.PER_SEGMENT_REPEAT,
            highlightSegments = listOf(a, b)
        )
        assertEquals(listOf("b", "a", "a"), TimelineEngine.orderedSourceSlices(tile).map { it.segmentId })
        assertEquals(4_000L, TimelineEngine.cycleDurationMs(tile))
    }

    @Test
    fun slicesLoopAndTrimExactlyToRequestedOutput() {
        val tile = tile(duration = 4_000L).copy(playbackSpeed = 2f)
        val slices = TimelineEngine.slicesToFill(tile, outputDurationMs = 5_000L, loop = true)
        assertEquals(10_000L, slices.sumOf { it.durationMs })
        assertEquals(3, slices.size)
        assertEquals(2_000L, slices.last().durationMs)
    }

    @Test
    fun noLoopHidesTileAfterItsNaturalEnd() {
        val tile = tile(duration = 2_000L).copy(repeatMode = RepeatMode.NONE)
        assertTrue(TimelineEngine.sample(tile, 1_999L, loopShortMedia = false).visible)
        assertFalse(TimelineEngine.sample(tile, 2_000L, loopShortMedia = false).visible)
    }

    @Test
    fun longestAndCustomOutputDurationsAreDeterministic() {
        val project = ProjectState(tiles = listOf(tile(duration = 3_000L), tile(duration = 8_000L).copy(playbackSpeed = 2f)))
        assertEquals(4_000L, TimelineEngine.outputDurationMs(project, ExportSettings()))
        assertEquals(33_000L, TimelineEngine.outputDurationMs(project, ExportSettings(lengthMode = OutputLengthMode.CUSTOM, customDurationMs = 33_000L)))
    }

    @Test
    fun audioOnceIsKeptOnlyForFirstVirtualCycle() {
        val tile = tile(duration = 1_000L).copy(repeatAudioMode = RepeatAudioMode.ONCE)
        val slices = TimelineEngine.slicesToFill(tile, 3_000L, loop = true)
        assertEquals(listOf(true, false, false), slices.map { it.includeAudio })
    }

    private fun tile(duration: Long = 10_000L) = VideoTileState(uri = "content://test/video", durationMs = duration, loopEnd = duration)
}
