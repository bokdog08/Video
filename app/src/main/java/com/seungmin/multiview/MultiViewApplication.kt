package com.seungmin.multiview

import android.app.Application
import androidx.work.Configuration
import com.seungmin.multiview.data.ProjectDatabase
import com.seungmin.multiview.data.ProjectRepository
import com.seungmin.multiview.util.MediaProbe

class MultiViewApplication : Application(), Configuration.Provider {
    val repository by lazy { ProjectRepository(ProjectDatabase.get(this).projectDao()) }
    val mediaProbe by lazy { MediaProbe(this) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
