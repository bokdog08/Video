package com.seungmin.multiview.playback

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.seungmin.multiview.audio.PanningAudioProcessor
import com.seungmin.multiview.model.RepeatMode
import com.seungmin.multiview.model.SourceMode
import com.seungmin.multiview.model.VideoTileState
import com.seungmin.multiview.model.ProjectState
import com.seungmin.multiview.timeline.TimelineEngine
import java.util.LinkedHashMap

@OptIn(UnstableApi::class)
class PlayerManager(private val context: Context) {
    private val handles = LinkedHashMap<String, TilePlayerHandle>(16, .75f, true)
    val decoderBudget: Int = detectDecoderBudget().coerceIn(2, 12)

    fun acquire(tile: VideoTileState, force: Boolean = false): TilePlayerHandle? {
        handles[tile.id]?.let { it.apply(tile); return it }
        if (handles.size >= decoderBudget) {
            if (!force) return null
            val victim = handles.entries.firstOrNull { !it.value.player.isPlaying } ?: handles.entries.firstOrNull()
            victim?.let { release(it.key) }
        }
        val processor = PanningAudioProcessor(tile.volume, tile.audioPan, tile.mute)
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .setAudioProcessors(arrayOf(processor))
                .build()
        }.setEnableDecoderFallback(true)

        val player = ExoPlayer.Builder(context, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                false
            )
            .build()
        return TilePlayerHandle(player, processor).also {
            handles[tile.id] = it
            it.apply(tile, forceMediaReload = true)
        }
    }

    fun get(id: String): TilePlayerHandle? = handles[id]

    fun sourcePosition(tile: VideoTileState): Long = handles[tile.id]?.sourcePosition(tile) ?: tile.playbackPosition

    fun update(tile: VideoTileState, masterMuted: Boolean) {
        handles[tile.id]?.apply(tile, masterMuted = masterMuted)
    }

    fun solo(id: String?) {
        handles.forEach { (tileId, handle) -> handle.player.volume = if (id == null || id == tileId) 1f else 0f }
    }

    fun pauseAll() = handles.values.forEach { it.player.pause() }

    fun startOutputPreview(project: ProjectState, loopShortMedia: Boolean) {
        project.tiles.forEach { tile ->
            (handles[tile.id] ?: acquire(tile))?.startOutputPreview(tile, project.masterMuted, loopShortMedia)
        }
    }

    fun restoreProject(project: ProjectState) {
        project.tiles.forEach { tile -> handles[tile.id]?.apply(tile, project.masterMuted, forceMediaReload = true) }
    }

    fun release(id: String) {
        handles.remove(id)?.player?.release()
    }

    fun retainOnly(ids: Set<String>) {
        handles.keys.toList().filterNot(ids::contains).forEach(::release)
    }

    fun releaseAll() {
        handles.values.forEach { it.player.release() }
        handles.clear()
    }

    private fun detectDecoderBudget(): Int = runCatching {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { type -> type.startsWith("video/") } }
        infos.maxOfOrNull { info ->
            info.supportedTypes.filter { it.startsWith("video/") }.maxOfOrNull { type ->
                runCatching { info.getCapabilitiesForType(type).maxSupportedInstances }.getOrDefault(4)
            } ?: 4
        } ?: 4
    }.getOrDefault(4)
}

class TilePlayerHandle(
    val player: ExoPlayer,
    private val panProcessor: PanningAudioProcessor
) {
    private var mediaFingerprint: Int = 0

    fun apply(tile: VideoTileState, masterMuted: Boolean = false, forceMediaReload: Boolean = false) {
        panProcessor.setMix(tile.volume, tile.audioPan, tile.mute || masterMuted)
        player.volume = 1f
        player.playbackParameters = PlaybackParameters(tile.playbackSpeed.coerceIn(.25f, 4f), 1f)
        val slices = TimelineEngine.orderedSourceSlices(tile)
        val fingerprint = listOf(tile.uri, tile.repeatMode, slices, tile.sourceMode).hashCode()
        if (forceMediaReload || fingerprint != mediaFingerprint) {
            val wasPlaying = if (forceMediaReload) tile.playing else player.isPlaying || tile.playing
            val items = slices.map { slice ->
                MediaItem.Builder()
                    .setUri(Uri.parse(tile.uri))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(slice.startMs)
                            .setEndPositionMs(slice.endMs)
                            .build()
                    )
                    .build()
            }
            player.setMediaItems(items, false)
            player.repeatMode = if (tile.repeatMode == RepeatMode.NONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, tile.sourceMode == SourceMode.VIDEO_ONLY)
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, tile.sourceMode == SourceMode.AUDIO_ONLY)
                .build()
            player.prepare()
            val startIndex = slices.indexOfFirst { tile.playbackPosition in it.startMs until it.endMs }.coerceAtLeast(0)
            val startSlice = slices[startIndex]
            player.seekTo(startIndex, (tile.playbackPosition - startSlice.startMs).coerceIn(0L, startSlice.durationMs - 1L))
            player.playWhenReady = wasPlaying
            mediaFingerprint = fingerprint
        } else if (tile.playing != player.isPlaying) {
            player.playWhenReady = tile.playing
        }
    }

    fun temporaryDoubleSpeed(enabled: Boolean, baseSpeed: Float) {
        val speed = if (enabled && baseSpeed < 2f) 2f else baseSpeed
        player.playbackParameters = PlaybackParameters(speed.coerceIn(.25f, 4f), 1f)
    }

    fun sourcePosition(tile: VideoTileState): Long {
        val slices = TimelineEngine.orderedSourceSlices(tile)
        val index = player.currentMediaItemIndex.coerceIn(0, slices.lastIndex)
        return (slices[index].startMs + player.currentPosition).coerceIn(0L, tile.durationMs)
    }

    fun startOutputPreview(tile: VideoTileState, masterMuted: Boolean, loopShortMedia: Boolean) {
        apply(tile, masterMuted, forceMediaReload = true)
        player.repeatMode = if (tile.repeatMode != RepeatMode.NONE || loopShortMedia) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        player.seekTo(0, 0L)
        player.play()
    }
}
