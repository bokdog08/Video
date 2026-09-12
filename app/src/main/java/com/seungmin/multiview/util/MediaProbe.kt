package com.seungmin.multiview.util

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.seungmin.multiview.model.VideoTileState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class MediaProbe(private val context: Context) {
    suspend fun inspect(uri: Uri): VideoTileState = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val tracks = extractorInfo(uri)
            val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                ?: tracks.first
            val rotated = rotation == 90 || rotation == 270
            VideoTileState(
                id = UUID.randomUUID().toString(),
                uri = uri.toString(),
                displayName = displayName(uri),
                durationMs = duration.coerceAtLeast(1L),
                sourceWidth = if (rotated) height else width,
                sourceHeight = if (rotated) width else height,
                sourceFps = fps.coerceIn(1f, 240f),
                hasAudio = tracks.second,
                sourceMode = if (tracks.second) com.seungmin.multiview.model.SourceMode.VIDEO_AND_AUDIO else com.seungmin.multiview.model.SourceMode.VIDEO_ONLY,
                loopEnd = duration.coerceAtLeast(1L)
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun isReadable(uri: Uri): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    private fun extractorInfo(uri: Uri): Pair<Float, Boolean> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val fps = (0 until extractor.trackCount).firstNotNullOfOrNull { index ->
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                } else null
            } ?: 30f
            val audio = (0 until extractor.trackCount).any { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            fps to audio
        } catch (_: Exception) {
            30f to true
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun displayName(uri: Uri): String {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "영상"
    }
}
