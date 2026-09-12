package com.seungmin.multiview.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable enum class SourceMode { VIDEO_AND_AUDIO, VIDEO_ONLY, AUDIO_ONLY }
@Serializable enum class RepeatMode { NONE, FULL, AB, HIGHLIGHT }
@Serializable enum class HighlightPlayMode { SEQUENTIAL, PER_SEGMENT_REPEAT, RANDOM }
@Serializable enum class ScaleMode { FIT, FILL, CROP }
@Serializable enum class ShortMediaMode { LOOP, HOLD_LAST_FRAME, BLACK }
@Serializable enum class OutputLengthMode { LONGEST, CUSTOM }
@Serializable enum class VideoCodec { H264, HEVC }
@Serializable enum class AudioExportMode { ALL_ACTIVE, SELECTED_ONLY }
@Serializable enum class RepeatAudioMode { WITH_VIDEO, ONCE }

@Serializable
data class HighlightSegment(
    val id: String = UUID.randomUUID().toString(),
    val startMs: Long,
    val endMs: Long,
    val order: Int = 0,
    val repeatCount: Int = 1,
    val enabled: Boolean = true,
    val name: String = "구간 ${order + 1}"
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(1L)
}

@Serializable
data class VideoTileState(
    val id: String = UUID.randomUUID().toString(),
    val uri: String,
    val displayName: String = "영상",
    val durationMs: Long = 1L,
    val sourceWidth: Int = 1920,
    val sourceHeight: Int = 1080,
    val sourceFps: Float = 30f,
    val hasAudio: Boolean = true,
    val playbackPosition: Long = 0L,
    val playing: Boolean = true,
    val playbackSpeed: Float = 1f,
    val volume: Float = 1f,
    val mute: Boolean = false,
    val audioPan: Float = 0f,
    val sourceMode: SourceMode = SourceMode.VIDEO_AND_AUDIO,
    val repeatMode: RepeatMode = RepeatMode.NONE,
    val loopStart: Long = 0L,
    val loopEnd: Long = durationMs,
    val highlightSegments: List<HighlightSegment> = emptyList(),
    val highlightPlayMode: HighlightPlayMode = HighlightPlayMode.SEQUENTIAL,
    val avoidSameRandomSegment: Boolean = true,
    val scaleMode: ScaleMode = ScaleMode.FIT,
    val contentScale: Float = 1f,
    val contentOffsetX: Float = 0f,
    val contentOffsetY: Float = 0f,
    val tileX: Float = 0f,
    val tileY: Float = 0f,
    val tileWidth: Float = 1f,
    val tileHeight: Float = 1f,
    val zIndex: Int = 0,
    val locked: Boolean = false,
    val audioSelectedForExport: Boolean = true,
    val repeatAudioMode: RepeatAudioMode = RepeatAudioMode.WITH_VIDEO,
    val uriReadable: Boolean = true,
    val errorMessage: String? = null
)

@Serializable
data class ProjectState(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "새 멀티뷰",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val tiles: List<VideoTileState> = emptyList(),
    val slotCount: Int = 4,
    val canvasAspectRatio: Float = 16f / 9f,
    val masterMuted: Boolean = false,
    val backgroundColor: Long = 0xFF000000,
    val selectedTileId: String? = null
)

@Serializable
data class ExportSettings(
    val lengthMode: OutputLengthMode = OutputLengthMode.LONGEST,
    val customDurationMs: Long = 60_000L,
    val shortMediaMode: ShortMediaMode = ShortMediaMode.LOOP,
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val useSourceFps: Boolean = true,
    val codec: VideoCodec = VideoCodec.H264,
    val audioMode: AudioExportMode = AudioExportMode.ALL_ACTIVE
)

@Serializable
data class ExportSnapshot(
    val project: ProjectState,
    val settings: ExportSettings,
    val outputDurationMs: Long,
    val createdAt: Long = System.currentTimeMillis()
)

data class SourceSlice(
    val startMs: Long,
    val endMs: Long,
    val segmentId: String? = null,
    val includeAudio: Boolean = true
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(1L)
}

data class TimelineSample(
    val visible: Boolean,
    val sourcePositionMs: Long,
    val segmentId: String?,
    val cycleIndex: Int
)
