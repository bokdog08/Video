package com.seungmin.multiview.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val projectJson: String,
    val updatedAt: Long
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latest(): ProjectEntity?

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [ProjectEntity::class], version = 1, exportSchema = false)
abstract class ProjectDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        @Volatile private var instance: ProjectDatabase? = null

        fun get(context: Context): ProjectDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ProjectDatabase::class.java,
                "multiview-projects.db"
            ).build().also { instance = it }
        }
    }
}
