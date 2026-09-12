package com.seungmin.multiview.export

import android.graphics.Matrix
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.effect.Crop
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.seungmin.multiview.audio.PanningAudioProcessor
import com.seungmin.multiview.model.*
import com.seungmin.multiview.timeline.TimelineEngine

@OptIn(UnstableApi::class)
object ExportCompositionFactory {
    fun create(snapshot: ExportSnapshot, backgroundUri: Uri, lastFrameUris: Map<String, Uri> = emptyMap()): Composition {
        val settings = snapshot.settings
        val background = MediaItem.Builder().setUri(backgroundUri).setImageDurationMs(snapshot.outputDurationMs).build()
        val backgroundItem = EditedMediaItem.Builder(background)
            .setFrameRate(settings.fps.coerceIn(1, 120))
            .setEffects(
                Effects(
                    emptyList(),
                    listOf(Presentation.createForWidthAndHeight(settings.width, settings.height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
                )
            )
            .build()
        val sequences = mutableListOf(EditedMediaItemSequence.withVideoFrom(listOf(backgroundItem)))
        val loopShortMedia = settings.shortMediaMode == ShortMediaMode.LOOP

        snapshot.project.tiles.sortedBy { it.zIndex }.forEach { tile ->
            val videoLoop = loopShortMedia || tile.repeatMode != RepeatMode.NONE
            val videoSlices = TimelineEngine.slicesToFill(tile, snapshot.outputDurationMs, videoLoop)
            if (tile.sourceMode != SourceMode.AUDIO_ONLY) {
                val videoItems = videoSlices.map { slice -> videoItem(tile, slice, settings) }.toMutableList()
                val usedDuration = videoSlices.sumOf { it.durationMs } / tile.playbackSpeed
                if (settings.shortMediaMode == ShortMediaMode.HOLD_LAST_FRAME && usedDuration < snapshot.outputDurationMs) {
                    lastFrameUris[tile.id]?.let { frame ->
                        videoItems += stillItem(tile, frame, (snapshot.outputDurationMs - usedDuration.toLong()).coerceAtLeast(1L), settings)
                    }
                }
                if (videoItems.isNotEmpty()) sequences += EditedMediaItemSequence.withVideoFrom(videoItems)
            }

            val audioAllowed = tile.hasAudio && tile.sourceMode != SourceMode.VIDEO_ONLY && !tile.mute && !snapshot.project.masterMuted &&
                when (settings.audioMode) {
                    AudioExportMode.ALL_ACTIVE -> true
                    AudioExportMode.SELECTED_ONLY -> tile.audioSelectedForExport
                }
            if (audioAllowed) {
                val audioLoop = videoLoop && tile.repeatAudioMode == RepeatAudioMode.WITH_VIDEO
                val audioSlices = TimelineEngine.slicesToFill(tile, snapshot.outputDurationMs, audioLoop)
                    .filter { it.includeAudio }
                val audioItems = audioSlices.map { slice -> audioItem(tile, slice) }
                if (audioItems.isNotEmpty()) sequences += EditedMediaItemSequence.withAudioFrom(audioItems)
            }
        }
        require(sequences.isNotEmpty()) { "내보낼 수 있는 활성 영상 또는 오디오가 없습니다." }
        return Composition.Builder(sequences).build()
    }

    private fun clippedMedia(uri: String, slice: SourceSlice): MediaItem = MediaItem.Builder()
        .setUri(Uri.parse(uri))
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(slice.startMs)
                .setEndPositionMs(slice.endMs)
                .build()
        )
        .build()

    private fun videoItem(tile: VideoTileState, slice: SourceSlice, settings: ExportSettings): EditedMediaItem {
        return EditedMediaItem.Builder(clippedMedia(tile.uri, slice))
            .setRemoveAudio(true)
            .setSpeed(ConstantSpeed(tile.playbackSpeed))
            .setEffects(Effects(emptyList(), videoEffects(tile, settings)))
            .build()
    }

    private fun audioItem(tile: VideoTileState, slice: SourceSlice): EditedMediaItem {
        return EditedMediaItem.Builder(clippedMedia(tile.uri, slice))
            .setRemoveVideo(true)
            .setSpeed(ConstantSpeed(tile.playbackSpeed))
            .setEffects(
                Effects(
                    listOf(PanningAudioProcessor(tile.volume, tile.audioPan, tile.mute)),
                    emptyList()
                )
            )
            .build()
    }

    private fun stillItem(
        tile: VideoTileState,
        frameUri: Uri,
        outputDurationMs: Long,
        settings: ExportSettings
    ): EditedMediaItem {
        val image = MediaItem.Builder().setUri(frameUri).setImageDurationMs(outputDurationMs).build()
        return EditedMediaItem.Builder(image)
            .setFrameRate(settings.fps.coerceIn(1, 120))
            .setEffects(Effects(emptyList(), videoEffects(tile, settings)))
            .build()
    }

    private fun videoEffects(tile: VideoTileState, settings: ExportSettings): List<Effect> {
        val layout = when (tile.scaleMode) {
            ScaleMode.FIT -> Presentation.LAYOUT_SCALE_TO_FIT
            ScaleMode.FILL, ScaleMode.CROP -> Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        }
        val result = mutableListOf<Effect>()
        if (tile.contentScale > 1.001f || tile.contentOffsetX != 0f || tile.contentOffsetY != 0f) {
            val half = (1f / tile.contentScale.coerceIn(1f, 8f)).coerceIn(.125f, 1f)
            val travel = 1f - half
            val centerX = (-tile.contentOffsetX).coerceIn(-1f, 1f) * travel
            val centerY = tile.contentOffsetY.coerceIn(-1f, 1f) * travel
            result += Crop(centerX - half, centerX + half, centerY - half, centerY + half)
        }
        result += Presentation.createForWidthAndHeight(settings.width, settings.height, layout)
        result += TileMatrixTransformation(tile)
        return result
    }
}

@OptIn(UnstableApi::class)
private class ConstantSpeed(private val speed: Float) : SpeedProvider {
    override fun getSpeed(timeUs: Long): Float = speed.coerceIn(.25f, 4f)
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
}

@OptIn(UnstableApi::class)
class TileMatrixTransformation(private val tile: VideoTileState) : MatrixTransformation {
    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val sx = tile.tileWidth
        val sy = tile.tileHeight
        val centerX = tile.tileX + tile.tileWidth / 2f
        val centerY = tile.tileY + tile.tileHeight / 2f
        val tx = centerX * 2f - 1f
        val ty = 1f - centerY * 2f
        return Matrix().apply {
            setScale(sx, sy)
            postTranslate(tx, ty)
        }
    }
}
