package com.seungmin.multiview.timeline

import com.seungmin.multiview.model.*
import kotlin.math.ceil
import kotlin.random.Random

/** Pure timeline math shared by live playback controllers and export composition creation. */
object TimelineEngine {
    private const val MAX_EXPORT_MS = 12L * 60L * 60L * 1000L

    fun orderedSourceSlices(tile: VideoTileState): List<SourceSlice> {
        val duration = tile.durationMs.coerceAtLeast(1L)
        return when (tile.repeatMode) {
            RepeatMode.NONE, RepeatMode.FULL -> listOf(SourceSlice(0L, duration))
            RepeatMode.AB -> {
                val start = tile.loopStart.coerceIn(0L, duration - 1L)
                listOf(SourceSlice(start, tile.loopEnd.coerceIn(start + 1L, duration)))
            }
            RepeatMode.HIGHLIGHT -> highlightSlices(tile).ifEmpty { listOf(SourceSlice(0L, duration)) }
        }
    }

    private fun highlightSlices(tile: VideoTileState): List<SourceSlice> {
        val duration = tile.durationMs.coerceAtLeast(1L)
        val valid = tile.highlightSegments
            .filter { it.enabled && it.endMs > it.startMs }
            .sortedBy { it.order }
        if (valid.isEmpty()) return emptyList()

        val ordered = when (tile.highlightPlayMode) {
            HighlightPlayMode.SEQUENTIAL -> valid
            HighlightPlayMode.PER_SEGMENT_REPEAT -> valid.asSequence()
                .flatMap { segment -> generateSequence { segment }.take(segment.repeatCount.coerceIn(1, 999)) }
                .take(50_000)
                .toList()
            HighlightPlayMode.RANDOM -> deterministicRandomOrder(tile, valid)
        }
        return ordered.map { segment ->
            val start = segment.startMs.coerceIn(0L, duration - 1L)
            SourceSlice(
                startMs = start,
                endMs = segment.endMs.coerceIn(start + 1L, duration),
                segmentId = segment.id,
                includeAudio = true
            )
        }
    }

    private fun deterministicRandomOrder(
        tile: VideoTileState,
        segments: List<HighlightSegment>
    ): List<HighlightSegment> {
        val random = Random(tile.id.hashCode())
        val count = segments.sumOf { it.repeatCount.coerceIn(1, 999) }.coerceAtLeast(segments.size).coerceAtMost(50_000)
        val result = ArrayList<HighlightSegment>(count)
        var last: HighlightSegment? = null
        repeat(count) {
            val candidates = if (tile.avoidSameRandomSegment && segments.size > 1) {
                segments.filterNot { it.id == last?.id }
            } else segments
            val chosen = candidates[random.nextInt(candidates.size)]
            result += chosen
            last = chosen
        }
        return result
    }

    fun cycleDurationMs(tile: VideoTileState): Long {
        val sourceMs = orderedSourceSlices(tile).sumOf { it.durationMs }
        return (sourceMs / tile.playbackSpeed.coerceIn(.05f, 8f)).toLong().coerceAtLeast(1L)
    }

    fun outputDurationMs(project: ProjectState, settings: ExportSettings): Long {
        if (settings.lengthMode == OutputLengthMode.CUSTOM) {
            return settings.customDurationMs.coerceIn(100L, MAX_EXPORT_MS)
        }
        return project.tiles.maxOfOrNull(::cycleDurationMs)?.coerceIn(100L, MAX_EXPORT_MS) ?: 1_000L
    }

    fun snapshot(project: ProjectState, settings: ExportSettings): ExportSnapshot {
        val frozen = project.copy(
            updatedAt = System.currentTimeMillis(),
            tiles = project.tiles.sortedBy { it.zIndex }.map { it.copy(highlightSegments = it.highlightSegments.toList()) }
        )
        return ExportSnapshot(frozen, settings, outputDurationMs(frozen, settings))
    }

    fun sample(tile: VideoTileState, outputTimeMs: Long, loopShortMedia: Boolean = true): TimelineSample {
        val slices = orderedSourceSlices(tile)
        val cycle = cycleDurationMs(tile)
        val isNaturallyLooping = tile.repeatMode != RepeatMode.NONE
        val shouldLoop = isNaturallyLooping || loopShortMedia
        if (!shouldLoop && outputTimeMs >= cycle) {
            return TimelineSample(false, tile.durationMs, null, 0)
        }
        val cycleIndex = (outputTimeMs / cycle).toInt()
        var remainingSourceMs = ((outputTimeMs % cycle) * tile.playbackSpeed).toLong()
        for (slice in slices) {
            if (remainingSourceMs < slice.durationMs) {
                return TimelineSample(true, slice.startMs + remainingSourceMs, slice.segmentId, cycleIndex)
            }
            remainingSourceMs -= slice.durationMs
        }
        val last = slices.last()
        return TimelineSample(true, last.endMs - 1L, last.segmentId, cycleIndex)
    }

    fun slicesToFill(tile: VideoTileState, outputDurationMs: Long, loop: Boolean): List<SourceSlice> {
        val base = orderedSourceSlices(tile)
        val sourceNeeded = ceil(outputDurationMs * tile.playbackSpeed).toLong().coerceAtLeast(1L)
        val result = mutableListOf<SourceSlice>()
        var accumulated = 0L
        var cycle = 0
        while (accumulated < sourceNeeded && cycle < 50_000) {
            for (slice in base) {
                if (accumulated >= sourceNeeded) break
                val remaining = sourceNeeded - accumulated
                val take = slice.durationMs.coerceAtMost(remaining)
                result += slice.copy(
                    endMs = slice.startMs + take,
                    includeAudio = slice.includeAudio &&
                        (tile.repeatAudioMode == RepeatAudioMode.WITH_VIDEO || cycle == 0)
                )
                accumulated += take
            }
            cycle++
            if (!loop) break
        }
        return result
    }
}
