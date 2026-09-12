package com.seungmin.multiview.export

import android.content.Context
import androidx.work.*
import com.seungmin.multiview.model.ExportSettings
import com.seungmin.multiview.model.ProjectState
import com.seungmin.multiview.timeline.TimelineEngine
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class ExportCoordinator(private val context: Context) {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    fun enqueue(project: ProjectState, settings: ExportSettings): UUID {
        val snapshot = TimelineEngine.snapshot(project, settings)
        val directory = File(context.filesDir, "export_snapshots").apply { mkdirs() }
        val snapshotFile = File(directory, "${UUID.randomUUID()}.json")
        snapshotFile.writeText(json.encodeToString(snapshot))
        val request = OneTimeWorkRequestBuilder<ExportWorker>()
            .setInputData(workDataOf(ExportWorker.KEY_SNAPSHOT_PATH to snapshotFile.absolutePath))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, java.util.concurrent.TimeUnit.SECONDS)
            .addTag(TAG_EXPORT)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "multiview_export_${snapshot.project.id}",
            ExistingWorkPolicy.REPLACE,
            request
        )
        return request.id
    }

    fun workInfo(id: UUID) = WorkManager.getInstance(context).getWorkInfoByIdFlow(id)
    fun allWorkInfos() = WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG_EXPORT)
    fun cancel(id: UUID) = WorkManager.getInstance(context).cancelWorkById(id)

    companion object { const val TAG_EXPORT = "multiview_export" }
}
