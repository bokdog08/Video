package com.seungmin.multiview

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.seungmin.multiview.data.ProjectRepository
import com.seungmin.multiview.model.*
import com.seungmin.multiview.playback.PlayerManager
import com.seungmin.multiview.export.ExportCoordinator
import com.seungmin.multiview.export.ExportWorker
import com.seungmin.multiview.timeline.TimelineEngine
import com.seungmin.multiview.util.MediaProbe
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class UiState(
    val project: ProjectState = ProjectState(),
    val selectedTileId: String? = null,
    val controlsTileId: String? = null,
    val editMode: Boolean = false,
    val fullscreenTileId: String? = null,
    val detailsTileId: String? = null,
    val highlightTileId: String? = null,
    val showExport: Boolean = false,
    val showProjects: Boolean = false,
    val globalControlsVisible: Boolean = true,
    val busy: Boolean = true,
    val message: String? = null,
    val export: ExportUiState = ExportUiState(),
    val preview: PreviewUiState = PreviewUiState()
)

data class ExportUiState(
    val workId: String? = null,
    val running: Boolean = false,
    val progress: Int = 0,
    val error: String? = null,
    val outputUri: String? = null,
    val outputName: String? = null
)

data class PreviewUiState(
    val running: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shortMediaMode: ShortMediaMode = ShortMediaMode.LOOP
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MultiViewApplication
    private val repository: ProjectRepository = app.repository
    private val mediaProbe: MediaProbe = app.mediaProbe
    private val exportCoordinator = ExportCoordinator(application)
    val playerManager = PlayerManager(application)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    val projects: StateFlow<List<ProjectState>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var controlsJob: Job? = null
    private var previewJob: Job? = null
    private var loaded = false
    private val ready = CompletableDeferred<Unit>()

    init {
        viewModelScope.launch {
            val project = runCatching { repository.loadLatest() }.getOrNull() ?: ProjectState()
            val checked = project.copy(tiles = project.tiles.map { tile ->
                val readable = mediaProbe.isReadable(Uri.parse(tile.uri))
                tile.copy(uriReadable = readable, errorMessage = if (readable) null else "파일 접근 권한이 없습니다")
            })
            _ui.value = UiState(project = checked, busy = false)
            loaded = true
            ready.complete(Unit)
        }
        viewModelScope.launch {
            ui.map { it.project }.distinctUntilChanged().debounce(650).collect { state ->
                if (loaded) repository.save(state)
            }
        }
        viewModelScope.launch {
            exportCoordinator.allWorkInfos().collect { infos ->
                val info = infos.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                    ?: infos.lastOrNull()
                    ?: return@collect
                applyWorkInfo(info)
            }
        }
    }

    fun importUris(uris: List<Uri>, replaceTileId: String? = null, preserveSettings: Boolean = true) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            ready.await()
            setBusy(true)
            val valid = uris.take(12).mapNotNull { uri ->
                takePermission(uri)
                runCatching { mediaProbe.inspect(uri) }.onFailure {
                    showMessage("영상을 열 수 없습니다: ${it.message ?: "손상되었거나 지원하지 않는 파일"}")
                }.getOrNull()
            }
            if (valid.isEmpty()) {
                setBusy(false)
                return@launch
            }
            mutateProject { project ->
                if (replaceTileId != null && valid.size == 1) {
                    project.copy(tiles = project.tiles.map { old ->
                        if (old.id != replaceTileId) old
                        else if (!preserveSettings) valid.first().copy(id = old.id, tileX = old.tileX, tileY = old.tileY, tileWidth = old.tileWidth, tileHeight = old.tileHeight, zIndex = old.zIndex)
                        else valid.first().copy(
                            id = old.id,
                            playbackSpeed = old.playbackSpeed,
                            volume = old.volume,
                            mute = old.mute,
                            audioPan = old.audioPan,
                            sourceMode = old.sourceMode,
                            repeatMode = old.repeatMode,
                            scaleMode = old.scaleMode,
                            contentScale = old.contentScale,
                            contentOffsetX = old.contentOffsetX,
                            contentOffsetY = old.contentOffsetY,
                            tileX = old.tileX,
                            tileY = old.tileY,
                            tileWidth = old.tileWidth,
                            tileHeight = old.tileHeight,
                            zIndex = old.zIndex,
                            locked = old.locked,
                            highlightSegments = old.highlightSegments.map { it.copy(endMs = it.endMs.coerceAtMost(valid.first().durationMs)) }.filter { it.endMs > it.startMs },
                            loopStart = old.loopStart.coerceAtMost(valid.first().durationMs - 1),
                            loopEnd = old.loopEnd.coerceAtMost(valid.first().durationMs).coerceAtLeast(1)
                        )
                    })
                } else {
                    val room = (12 - project.tiles.size).coerceAtLeast(0)
                    val incoming = valid.take(room)
                    val finalCount = project.tiles.size + incoming.size
                    val slots = nextSlotCount(finalCount)
                    val newTiles = incoming.mapIndexed { offset, tile ->
                        val index = project.tiles.size + offset
                        val cell = LayoutPresets.defaultCell(index.coerceAtMost(slots - 1), slots)
                        tile.copy(
                            tileX = cell.x + .004f,
                            tileY = cell.y + .004f,
                            tileWidth = cell.w - .008f,
                            tileHeight = cell.h - .008f,
                            zIndex = index
                        )
                    }
                    project.copy(tiles = project.tiles + newTiles, slotCount = slots)
                }
            }
            setBusy(false)
        }
    }

    fun importSharedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            ready.await()
            createProject("공유 영상 ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
            importUris(uris)
        }
    }

    fun selectTile(id: String) {
        _ui.update { it.copy(selectedTileId = id, controlsTileId = id) }
        resetControlsTimer()
    }

    fun controlsInteracted() = resetControlsTimer()
    fun hideControls() { controlsJob?.cancel(); _ui.update { it.copy(controlsTileId = null) } }
    fun toggleEditMode() { _ui.update { it.copy(editMode = !it.editMode, controlsTileId = null) } }
    fun toggleFullscreen(id: String) { _ui.update { it.copy(fullscreenTileId = if (it.fullscreenTileId == id) null else id, controlsTileId = null) } }
    fun showDetails(id: String?) { _ui.update { it.copy(detailsTileId = id) }; if (id != null) resetControlsTimer() }
    fun showHighlight(id: String?) { _ui.update { it.copy(highlightTileId = id, detailsTileId = null) } }
    fun showExport(show: Boolean) {
        _ui.update {
            it.copy(
                showExport = show,
                export = if (show && !it.export.running) ExportUiState() else it.export
            )
        }
    }
    fun showProjects(show: Boolean) { _ui.update { it.copy(showProjects = show) } }
    fun toggleGlobalControls() {
        _ui.update { it.copy(globalControlsVisible = !it.globalControlsVisible, controlsTileId = null) }
        resetGlobalTimer()
    }
    fun showGlobalControls() { _ui.update { it.copy(globalControlsVisible = true) }; resetGlobalTimer() }

    fun startPreview(settings: ExportSettings) {
        val snapshot = TimelineEngine.snapshot(_ui.value.project, settings)
        if (snapshot.project.tiles.isEmpty()) { showMessage("먼저 영상을 추가하세요."); return }
        previewJob?.cancel()
        _ui.update { it.copy(showExport = false, controlsTileId = null, globalControlsVisible = false, preview = PreviewUiState(true, 0L, snapshot.outputDurationMs, settings.shortMediaMode)) }
        playerManager.startOutputPreview(snapshot.project, settings.shortMediaMode == ShortMediaMode.LOOP)
        previewJob = viewModelScope.launch {
            val started = android.os.SystemClock.elapsedRealtime()
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                if (elapsed >= snapshot.outputDurationMs) break
                _ui.update { it.copy(preview = it.preview.copy(positionMs = elapsed)) }
                delay(100)
            }
            stopPreview()
        }
    }

    fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        playerManager.restoreProject(_ui.value.project)
        _ui.update { it.copy(preview = PreviewUiState(), globalControlsVisible = true) }
    }

    fun updateTile(id: String, update: (VideoTileState) -> VideoTileState) {
        mutateProject { project -> project.copy(tiles = project.tiles.map { if (it.id == id) sanitize(update(it)) else it }) }
    }

    fun updatePosition(id: String, x: Float, y: Float, width: Float, height: Float) {
        updateTile(id) { tile ->
            if (tile.locked) tile else {
                val safeWidth = width.coerceIn(.08f, 1f)
                val safeHeight = height.coerceIn(.08f, 1f)
                tile.copy(
                    tileX = x.coerceIn(0f, 1f - safeWidth),
                    tileY = y.coerceIn(0f, 1f - safeHeight),
                    tileWidth = safeWidth,
                    tileHeight = safeHeight
                )
            }
        }
    }

    fun applyLayout(count: Int) = mutateProject { LayoutPresets.apply(it, count) }

    fun removeTile(id: String) {
        playerManager.release(id)
        mutateProject { project ->
            val tiles = project.tiles.filterNot { it.id == id }.mapIndexed { index, tile -> tile.copy(zIndex = index) }
            project.copy(tiles = tiles, selectedTileId = null)
        }
        _ui.update { it.copy(selectedTileId = null, controlsTileId = null, detailsTileId = null) }
    }

    fun moveLayer(id: String, delta: Int, absolute: Boolean = false) {
        mutateProject { project ->
            val sorted = project.tiles.sortedBy { it.zIndex }.toMutableList()
            val from = sorted.indexOfFirst { it.id == id }
            if (from < 0) return@mutateProject project
            val to = when {
                absolute && delta > 0 -> sorted.lastIndex
                absolute -> 0
                else -> (from + delta).coerceIn(0, sorted.lastIndex)
            }
            val item = sorted.removeAt(from)
            sorted.add(to, item)
            project.copy(tiles = sorted.mapIndexed { index, tile -> tile.copy(zIndex = index) })
        }
    }

    fun setMasterMuted(muted: Boolean) = mutateProject { it.copy(masterMuted = muted) }

    fun soloTile(id: String) = mutateProject { project ->
        project.copy(masterMuted = false, tiles = project.tiles.map { tile -> tile.copy(mute = tile.id != id) })
    }

    fun capturePlaybackPositions() {
        if (!ready.isCompleted) return
        mutateProject { project ->
            project.copy(tiles = project.tiles.map { tile -> tile.copy(playbackPosition = playerManager.sourcePosition(tile)) })
        }
        saveNow()
    }

    fun createProject(name: String = "새 멀티뷰") {
        playerManager.releaseAll()
        _ui.value = UiState(project = ProjectState(name = name), busy = false)
    }

    fun renameProject(name: String) = mutateProject { it.copy(name = name.ifBlank { "새 멀티뷰" }) }

    fun openProject(id: String) {
        viewModelScope.launch {
            setBusy(true)
            repository.load(id)?.let { project ->
                playerManager.releaseAll()
                val checked = project.copy(tiles = project.tiles.map { tile ->
                    val readable = mediaProbe.isReadable(Uri.parse(tile.uri))
                    tile.copy(uriReadable = readable, errorMessage = if (readable) null else "원본 영상 접근 권한이 만료되었습니다")
                })
                _ui.value = UiState(project = checked, busy = false)
            } ?: setBusy(false)
        }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            repository.delete(id)
            if (_ui.value.project.id == id) createProject()
        }
    }

    fun saveNow() { viewModelScope.launch { repository.save(_ui.value.project) } }

    fun startExport(settings: ExportSettings) {
        val project = _ui.value.project
        if (project.tiles.isEmpty()) { showMessage("먼저 영상을 추가하세요."); return }
        val workId = exportCoordinator.enqueue(project, settings)
        _ui.update { it.copy(showExport = true, export = ExportUiState(workId.toString(), true)) }
    }

    fun cancelExport() {
        _ui.value.export.workId?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let(exportCoordinator::cancel)
        _ui.update { it.copy(export = it.export.copy(running = false, error = "영상 제작이 취소되었습니다.")) }
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    override fun onCleared() {
        previewJob?.cancel()
        playerManager.releaseAll()
        super.onCleared()
    }

    private fun mutateProject(block: (ProjectState) -> ProjectState) {
        _ui.update { state -> state.copy(project = block(state.project).copy(updatedAt = System.currentTimeMillis())) }
    }

    private fun sanitize(tile: VideoTileState): VideoTileState {
        val width = tile.tileWidth.coerceIn(.08f, 1f)
        val height = tile.tileHeight.coerceIn(.08f, 1f)
        val loopStart = tile.loopStart.coerceIn(0L, tile.durationMs - 1L)
        val loopEnd = tile.loopEnd.coerceIn(loopStart + 1L, tile.durationMs)
        return tile.copy(
            playbackSpeed = tile.playbackSpeed.coerceIn(.25f, 4f),
            volume = tile.volume.coerceIn(0f, 1f),
            audioPan = tile.audioPan.coerceIn(-1f, 1f),
            contentScale = tile.contentScale.coerceIn(1f, 8f),
            contentOffsetX = tile.contentOffsetX.coerceIn(-2f, 2f),
            contentOffsetY = tile.contentOffsetY.coerceIn(-2f, 2f),
            tileX = tile.tileX.coerceIn(0f, 1f - width),
            tileY = tile.tileY.coerceIn(0f, 1f - height),
            tileWidth = width,
            tileHeight = height,
            loopStart = loopStart,
            loopEnd = loopEnd
        )
    }

    private fun takePermission(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun resetControlsTimer() {
        controlsJob?.cancel()
        controlsJob = viewModelScope.launch {
            delay(4_000)
            _ui.update { it.copy(controlsTileId = null) }
        }
    }

    private fun resetGlobalTimer() {
        controlsJob?.cancel()
        controlsJob = viewModelScope.launch {
            delay(4_000)
            if (!_ui.value.editMode && _ui.value.project.tiles.isNotEmpty()) {
                _ui.update { it.copy(globalControlsVisible = false) }
            }
        }
    }

    private fun showMessage(message: String) = _ui.update { it.copy(message = message) }
    private fun setBusy(value: Boolean) = _ui.update { it.copy(busy = value) }

    private fun applyWorkInfo(info: WorkInfo) {
        val progress = info.progress.getInt(ExportWorker.KEY_PROGRESS, 0)
        val error = info.outputData.getString(ExportWorker.KEY_ERROR) ?: when (info.state) {
            WorkInfo.State.CANCELLED -> "영상 제작이 취소되었습니다."
            WorkInfo.State.FAILED -> "영상 제작 작업이 실패했습니다."
            else -> null
        }
        val uri = info.outputData.getString(ExportWorker.KEY_OUTPUT_URI)
        val name = info.outputData.getString(ExportWorker.KEY_OUTPUT_NAME)
        _ui.update { state ->
            state.copy(export = ExportUiState(
                workId = info.id.toString(),
                running = info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED,
                progress = progress,
                error = error,
                outputUri = uri,
                outputName = name
            ))
        }
    }

    private fun nextSlotCount(size: Int): Int = when {
        size <= 1 -> 1
        size <= 2 -> 2
        size <= 4 -> 4
        size <= 6 -> 6
        size <= 9 -> 9
        else -> 12
    }
}
