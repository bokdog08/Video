package com.seungmin.multiview.export

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.*
import androidx.work.*
import com.seungmin.multiview.R
import com.seungmin.multiview.model.ExportSnapshot
import com.seungmin.multiview.model.ShortMediaMode
import com.seungmin.multiview.model.VideoCodec
import com.seungmin.multiview.timeline.TimelineEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

@OptIn(UnstableApi::class)
class ExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val notificationManager = context.getSystemService<NotificationManager>()!!

    override suspend fun doWork(): Result {
        createChannel()
        setForeground(foreground(0, "영상 제작 준비 중"))
        val snapshotPath = inputData.getString(KEY_SNAPSHOT_PATH) ?: return Result.failure(errorData("Snapshot이 없습니다."))
        val snapshotFile = File(snapshotPath)
        val snapshot = runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString<ExportSnapshot>(snapshotFile.readText())
        }.getOrElse { return Result.failure(errorData("프로젝트 Snapshot을 읽지 못했습니다.")) }

        val unreadable = snapshot.project.tiles.firstOrNull { tile ->
            runCatching { applicationContext.contentResolver.openFileDescriptor(Uri.parse(tile.uri), "r")?.close(); false }.getOrElse { true }
        }
        if (unreadable != null) return Result.failure(errorData("원본 파일에 접근할 수 없습니다: ${unreadable.displayName}"))

        val tempOutput = File(applicationContext.cacheDir, "export_${id}.mp4").also { if (it.exists()) it.delete() }
        val framesDir = File(applicationContext.cacheDir, "export_frames_${id}").apply { mkdirs() }
        return try {
            val lastFrames = if (snapshot.settings.shortMediaMode == ShortMediaMode.HOLD_LAST_FRAME) {
                createLastFrames(snapshot, framesDir)
            } else emptyMap()
            val background = createBlackFrame(framesDir)
            val composition = ExportCompositionFactory.create(snapshot, background, lastFrames)
            runTransformer(composition, snapshot, tempOutput)
            val name = outputName()
            val outputUri = publish(tempOutput, name)
            Result.success(workDataOf(KEY_OUTPUT_URI to outputUri.toString(), KEY_OUTPUT_NAME to name))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            Result.failure(errorData("영상 제작이 취소되었습니다."))
        } catch (error: Throwable) {
            Result.failure(errorData(friendlyError(error)))
        } finally {
            tempOutput.delete()
            framesDir.deleteRecursively()
            snapshotFile.delete()
            notificationManager.cancel(NOTIFICATION_ID)
        }
    }

    private suspend fun runTransformer(composition: Composition, snapshot: ExportSnapshot, output: File) =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val transformer = Transformer.Builder(applicationContext)
                    .setVideoMimeType(if (snapshot.settings.codec == VideoCodec.HEVC) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }

                        override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                            if (continuation.isActive) continuation.resumeWith(kotlin.Result.failure(exception))
                        }
                    })
                    .build()
                continuation.invokeOnCancellation { transformer.cancel() }
                transformer.start(composition, output.absolutePath)
                launch {
                    val holder = ProgressHolder()
                    while (isActive && continuation.isActive) {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            setProgress(workDataOf(KEY_PROGRESS to holder.progress))
                            setForeground(foreground(holder.progress, "영상 제작 중 ${holder.progress}%"))
                        }
                        delay(500)
                    }
                }
            }
        }

    private suspend fun createLastFrames(snapshot: ExportSnapshot, directory: File): Map<String, Uri> = withContext(Dispatchers.IO) {
        snapshot.project.tiles.mapNotNull { tile ->
            val cycle = TimelineEngine.cycleDurationMs(tile)
            if (cycle >= snapshot.outputDurationMs || tile.sourceMode == com.seungmin.multiview.model.SourceMode.AUDIO_ONLY) return@mapNotNull null
            val lastSlice = TimelineEngine.orderedSourceSlices(tile).last()
            val file = File(directory, "${tile.id}.jpg")
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(applicationContext, Uri.parse(tile.uri))
                val frame = retriever.getFrameAtTime((lastSlice.endMs - 1L) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: return@mapNotNull null
                file.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                frame.recycle()
                tile.id to Uri.fromFile(file)
            } catch (_: Throwable) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }.toMap()
    }

    private suspend fun createBlackFrame(directory: File): Uri = withContext(Dispatchers.IO) {
        val file = File(directory, "background.jpg")
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        Uri.fromFile(file)
    }

    private suspend fun publish(source: File, name: String): Uri = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MultiViewStudio")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = applicationContext.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore에 출력 파일을 만들 수 없습니다.")
        try {
            resolver.openOutputStream(uri, "w")!!.use { output -> source.inputStream().use { it.copyTo(output) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun outputName(): String = "MultiView_${SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())}.mp4"

    private fun foreground(progress: Int, text: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_export)
            .setContentTitle("멀티뷰 스튜디오")
            .setContentText(text)
            .setOngoing(true)
            .setProgress(100, progress, progress <= 0)
            .build()
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "영상 제작", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("codec", true) -> "이 기기에서 선택한 코덱 또는 동시 디코딩을 지원하지 않습니다. H.264나 낮은 해상도로 다시 시도하세요."
            message.contains("space", true) || message.contains("ENOSPC", true) -> "저장 공간이 부족합니다."
            message.contains("permission", true) -> "원본 영상 또는 저장 위치의 권한이 없습니다."
            else -> "영상 제작 실패: ${message.ifBlank { error::class.java.simpleName }}"
        }
    }

    private fun errorData(message: String) = workDataOf(KEY_ERROR to message)

    companion object {
        const val KEY_SNAPSHOT_PATH = "snapshot_path"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_OUTPUT_NAME = "output_name"
        private const val CHANNEL_ID = "multiview_export"
        private const val NOTIFICATION_ID = 4501
    }
}
