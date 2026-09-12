package com.seungmin.multiview.data

import com.seungmin.multiview.model.ProjectState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class ProjectRepository(private val dao: ProjectDao) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun observeAll(): Flow<List<ProjectState>> = dao.observeAll().map { entities ->
        entities.mapNotNull(::decode)
    }

    suspend fun loadLatest(): ProjectState? = dao.latest()?.let(::decode)

    suspend fun load(id: String): ProjectState? = dao.byId(id)?.let(::decode)

    suspend fun save(state: ProjectState) {
        val current = state.copy(updatedAt = System.currentTimeMillis())
        dao.upsert(ProjectEntity(current.id, current.name, json.encodeToString(current), current.updatedAt))
    }

    suspend fun delete(id: String) = dao.delete(id)

    private fun decode(entity: ProjectEntity): ProjectState? = runCatching {
        json.decodeFromString<ProjectState>(entity.projectJson)
    }.getOrNull()
}
