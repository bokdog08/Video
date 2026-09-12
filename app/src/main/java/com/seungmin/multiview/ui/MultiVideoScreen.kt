@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.seungmin.multiview.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.seungmin.multiview.MainViewModel
import com.seungmin.multiview.R
import com.seungmin.multiview.model.*
import com.seungmin.multiview.playback.TilePlayerHandle
import com.seungmin.multiview.timeline.TimelineEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
fun MultiVideoScreen(
    viewModel: MainViewModel,
    onPickOne: (tileId: String?, preserveSettings: Boolean) -> Unit,
    onPickMany: () -> Unit
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val project = state.project

    LaunchedEffect(project.tiles, project.masterMuted) {
        viewModel.playerManager.retainOnly(project.tiles.map { it.id }.toSet())
        project.tiles.forEach { viewModel.playerManager.update(it, project.masterMuted) }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }

    BackHandlerCompat(enabled = state.preview.running || state.fullscreenTileId != null || state.detailsTileId != null || state.highlightTileId != null) {
        when {
            state.preview.running -> viewModel.stopPreview()
            state.highlightTileId != null -> viewModel.showHighlight(null)
            state.detailsTileId != null -> viewModel.showDetails(null)
            state.fullscreenTileId != null -> viewModel.toggleFullscreen(state.fullscreenTileId!!)
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { viewModel.toggleGlobalControls() }) }
    ) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val density = LocalDensity.current
        val fullscreen = state.fullscreenTileId
        val visibleTiles = if (fullscreen == null) project.tiles else project.tiles.filter { it.id == fullscreen }

        if (fullscreen == null) {
            repeat(project.slotCount.coerceIn(1, 12)) { index ->
                if (index >= project.tiles.size) {
                    val cell = LayoutPresets.defaultCell(index, project.slotCount)
                    val x = (cell.x * widthPx).roundToInt()
                    val y = (cell.y * heightPx).roundToInt()
                    val w = with(density) { (cell.w * widthPx).toDp() }
                    val h = with(density) { (cell.h * heightPx).toDp() }
                    EmptyTile(
                        Modifier.offset { IntOffset(x, y) }.size(w, h),
                        onPickOne,
                        onPickMany
                    )
                }
            }
        }

        visibleTiles.sortedBy { it.zIndex }.forEach { tile ->
            val x = if (fullscreen != null) 0 else (tile.tileX * widthPx).roundToInt()
            val y = if (fullscreen != null) 0 else (tile.tileY * heightPx).roundToInt()
            val w = if (fullscreen != null) maxWidth else with(density) { (tile.tileWidth * widthPx).toDp() }
            val h = if (fullscreen != null) maxHeight else with(density) { (tile.tileHeight * heightPx).toDp() }
            VideoTile(
                tile = tile,
                masterMuted = project.masterMuted,
                selected = state.selectedTileId == tile.id,
                controlsVisible = state.controlsTileId == tile.id,
                editMode = state.editMode && fullscreen == null,
                preview = state.preview,
                playerManager = viewModel.playerManager,
                canvasWidthPx = widthPx,
                canvasHeightPx = heightPx,
                modifier = Modifier
                    .offset { IntOffset(x, y) }
                    .size(w, h)
                    .graphicsLayer { shadowElevation = if (state.selectedTileId == tile.id) 12f else 0f },
                onSelect = { viewModel.selectTile(tile.id) },
                onInteract = viewModel::controlsInteracted,
                onUpdate = { transform -> viewModel.updateTile(tile.id, transform) },
                onPosition = { nx, ny, nw, nh -> viewModel.updatePosition(tile.id, nx, ny, nw, nh) },
                onDoubleTap = { viewModel.toggleFullscreen(tile.id) },
                onDetails = { viewModel.showDetails(tile.id) }
            )
        }

        if (state.globalControlsVisible || state.editMode || project.tiles.isEmpty()) {
            GlobalToolbar(
                project = project,
                editMode = state.editMode,
                onPickMany = onPickMany,
                onEdit = viewModel::toggleEditMode,
                onLayout = viewModel::applyLayout,
                onProjects = { viewModel.showProjects(true) },
                onMute = { viewModel.setMasterMuted(!project.masterMuted) },
                onExport = { viewModel.showExport(true) },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
            )
        }

        if (state.busy) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }

        if (state.preview.running) {
            Surface(
                Modifier.align(Alignment.TopEnd).padding(12.dp),
                color = Color(0xDC171717),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(Modifier.padding(start = 14.dp, end = 5.dp).height(42.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(190.dp)) {
                        Text("최종 결과 미리보기", style = MaterialTheme.typography.labelMedium)
                        LinearProgressIndicator(
                            progress = { state.preview.positionMs.toFloat() / state.preview.durationMs.coerceAtLeast(1L) },
                            modifier = Modifier.fillMaxWidth().height(3.dp)
                        )
                    }
                    Text("${formatTime(state.preview.positionMs)} / ${formatTime(state.preview.durationMs)}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 10.dp))
                    IconButton(onClick = viewModel::stopPreview) { Icon(Icons.Default.Close, "미리보기 종료") }
                }
            }
        }
    }

    state.detailsTileId?.let { id ->
        project.tiles.firstOrNull { it.id == id }?.let { tile ->
            TileSettingsDialog(
                tile = tile,
                onDismiss = { viewModel.showDetails(null) },
                onUpdate = { transform -> viewModel.updateTile(id, transform); viewModel.controlsInteracted() },
                onHighlight = { viewModel.showHighlight(id) },
                onSolo = { viewModel.soloTile(id) },
                onReplace = { preserve -> viewModel.showDetails(null); onPickOne(id, preserve) },
                onFullscreen = { viewModel.showDetails(null); viewModel.toggleFullscreen(id) },
                onLayer = { delta, absolute -> viewModel.moveLayer(id, delta, absolute) },
                onRemove = { viewModel.removeTile(id) }
            )
        }
    }

    state.highlightTileId?.let { id ->
        project.tiles.firstOrNull { it.id == id }?.let { tile ->
            HighlightEditorDialog(
                tile = tile,
                playerManager = viewModel.playerManager,
                onDismiss = { viewModel.showHighlight(null) },
                onSave = { updated -> viewModel.updateTile(id) { updated }; viewModel.showHighlight(null) }
            )
        }
    }

    if (state.showExport) {
        ExportDialog(
            project = project,
            exportState = state.export,
            onDismiss = { viewModel.showExport(false) },
            onPreview = { settings -> viewModel.startPreview(settings) },
            onStart = viewModel::startExport,
            onCancel = viewModel::cancelExport,
            onOpen = { uri -> viewUri(context, uri) },
            onShare = { uri -> shareUri(context, uri) },
            onLocate = { browseVideos(context) }
        )
    }

    if (state.showProjects) {
        val projects by viewModel.projects.collectAsStateWithLifecycle()
        ProjectDialog(
            current = project,
            projects = projects,
            onDismiss = { viewModel.showProjects(false) },
            onNew = { viewModel.createProject(); viewModel.showProjects(false) },
            onOpen = { viewModel.openProject(it); viewModel.showProjects(false) },
            onRename = viewModel::renameProject,
            onDelete = viewModel::deleteProject
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmptyTile(
    modifier: Modifier,
    onPickOne: (String?, Boolean) -> Unit,
    onPickMany: () -> Unit
) {
    Box(
        modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0D0D0D))
            .combinedClickable(onClick = { onPickOne(null, true) }, onLongClick = onPickMany),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Add, "영상 추가", tint = Color(0xFF777777), modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun VideoTile(
    tile: VideoTileState,
    masterMuted: Boolean,
    selected: Boolean,
    controlsVisible: Boolean,
    editMode: Boolean,
    preview: com.seungmin.multiview.PreviewUiState,
    playerManager: com.seungmin.multiview.playback.PlayerManager,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    modifier: Modifier,
    onSelect: () -> Unit,
    onInteract: () -> Unit,
    onUpdate: ((VideoTileState) -> VideoTileState) -> Unit,
    onPosition: (Float, Float, Float, Float) -> Unit,
    onDoubleTap: () -> Unit,
    onDetails: () -> Unit
) {
    var handle by remember(tile.id) { mutableStateOf(playerManager.acquire(tile)) }
    var playbackError by remember(tile.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val activeHandle = handle?.takeIf { playerManager.get(tile.id) === it }
    val visibleInPreview = !preview.running || preview.shortMediaMode != ShortMediaMode.BLACK ||
        TimelineEngine.sample(tile, preview.positionMs, loopShortMedia = false).visible

    LaunchedEffect(tile, masterMuted, activeHandle) { activeHandle?.apply(tile, masterMuted) }
    DisposableEffect(activeHandle) {
        val observedPlayer = activeHandle?.player
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = if (error.errorCodeName.contains("DECOD", true)) {
                    "디코더를 사용할 수 없습니다\n탭하여 다시 시도"
                } else "영상을 재생할 수 없습니다\n탭하여 다시 시도"
            }
        }
        observedPlayer?.addListener(listener)
        onDispose { observedPlayer?.removeListener(listener) }
    }
    val moveModifier = if (editMode && !tile.locked) {
        Modifier.pointerInput(tile.id, tile.tileX, tile.tileY) {
            detectDragGestures { change, drag ->
                change.consume()
                onPosition(
                    tile.tileX + drag.x / canvasWidthPx,
                    tile.tileY + drag.y / canvasHeightPx,
                    tile.tileWidth,
                    tile.tileHeight
                )
            }
        }
    } else if (!editMode) {
        Modifier.pointerInput(tile.id, tile.contentScale, tile.contentOffsetX, tile.contentOffsetY) {
            detectTransformGestures { _, pan, zoom, _ ->
                if (zoom != 1f || (tile.contentScale > 1f && sqrt(pan.x * pan.x + pan.y * pan.y) > 0f)) {
                    onUpdate {
                        it.copy(
                            contentScale = (it.contentScale * zoom).coerceIn(1f, 8f),
                            contentOffsetX = (it.contentOffsetX + pan.x / size.width.coerceAtLeast(1).toFloat()).coerceIn(-2f, 2f),
                            contentOffsetY = (it.contentOffsetY + pan.y / size.height.coerceAtLeast(1).toFloat()).coerceIn(-2f, 2f)
                        )
                    }
                }
            }
        }
    } else Modifier

    Box(
        modifier
            .clipToBounds()
            .background(Color.Black)
            .then(moveModifier)
            .pointerInput(tile.id, editMode, activeHandle) {
                detectTapGestures(
                    onTap = {
                        if (activeHandle == null || playbackError != null) {
                            playerManager.release(tile.id)
                            handle = playerManager.acquire(tile, force = true)
                            playbackError = null
                        }
                        onSelect()
                    },
                    onDoubleTap = { onDoubleTap() },
                    onPress = {
                        var fast = false
                        val job = scope.launch {
                            delay(450)
                            fast = true
                            activeHandle?.temporaryDoubleSpeed(true, tile.playbackSpeed)
                        }
                        tryAwaitRelease()
                        job.cancel()
                        if (fast) activeHandle?.temporaryDoubleSpeed(false, tile.playbackSpeed)
                    }
                )
            }
    ) {
        val player = activeHandle?.player
        if (player != null && tile.uriReadable && playbackError == null && visibleInPreview) {
            AndroidView(
                factory = { context ->
                    (LayoutInflater.from(context).inflate(R.layout.player_texture_view, null, false) as PlayerView).apply {
                        this.player = player
                    }
                },
                update = { view ->
                    view.player = player
                    view.resizeMode = when (tile.scaleMode) {
                        ScaleMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        ScaleMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        ScaleMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    }
                },
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = tile.contentScale
                        scaleY = tile.contentScale
                        translationX = tile.contentOffsetX * size.width
                        translationY = tile.contentOffsetY * size.height
                    }
            )
        } else {
            Box(Modifier.matchParentSize().background(Color(0xFF111111)), contentAlignment = Alignment.Center) {
                if (visibleInPreview) {
                    Text(
                        playbackError ?: if (!tile.uriReadable) "파일 접근 권한 만료\n⋮ → 영상 교체" else "동시 디코더 한도\n탭하여 이 영상 활성화",
                        color = Color(0xFFAAAAAA),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        if (editMode) {
            Box(
                Modifier.matchParentSize().graphicsLayer {
                    clip = true
                    shape = RoundedCornerShape(4.dp)
                }
            ) {
                Box(Modifier.matchParentSize().background(if (selected) Color.White.copy(.10f) else Color.Transparent))
                Icon(
                    if (tile.locked) Icons.Default.Lock else Icons.Default.OpenWith,
                    null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(24.dp)
                )
                if (!tile.locked) {
                    ResizeHandle(Modifier.align(Alignment.CenterStart).width(12.dp).fillMaxHeight()) { dx, _ ->
                        val normalized = dx / canvasWidthPx
                        onPosition(tile.tileX + normalized, tile.tileY, tile.tileWidth - normalized, tile.tileHeight)
                    }
                    ResizeHandle(Modifier.align(Alignment.CenterEnd).width(12.dp).fillMaxHeight()) { dx, _ ->
                        onPosition(tile.tileX, tile.tileY, tile.tileWidth + dx / canvasWidthPx, tile.tileHeight)
                    }
                    ResizeHandle(Modifier.align(Alignment.TopCenter).height(12.dp).fillMaxWidth()) { _, dy ->
                        val normalized = dy / canvasHeightPx
                        onPosition(tile.tileX, tile.tileY + normalized, tile.tileWidth, tile.tileHeight - normalized)
                    }
                    ResizeHandle(Modifier.align(Alignment.BottomCenter).height(12.dp).fillMaxWidth()) { _, dy ->
                        onPosition(tile.tileX, tile.tileY, tile.tileWidth, tile.tileHeight + dy / canvasHeightPx)
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(28.dp)
                            .background(Color.White, CircleShape)
                            .pointerInput(tile.id, tile.tileWidth, tile.tileHeight) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    onPosition(
                                        tile.tileX,
                                        tile.tileY,
                                        tile.tileWidth + drag.x / canvasWidthPx,
                                        tile.tileHeight + drag.y / canvasHeightPx
                                    )
                                }
                            }
                    )
                }
            }
        }

        if (controlsVisible && !editMode) {
            TileControls(
                tile = tile,
                handle = activeHandle,
                onInteract = onInteract,
                onUpdate = onUpdate,
                onDetails = onDetails
            )
        }
    }
}

@Composable
private fun ResizeHandle(modifier: Modifier, onDrag: (Float, Float) -> Unit) {
    Box(
        modifier
            .background(Color.White.copy(.10f))
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            }
    )
}

@Composable
private fun TileControls(
    tile: VideoTileState,
    handle: TilePlayerHandle?,
    onInteract: () -> Unit,
    onUpdate: ((VideoTileState) -> VideoTileState) -> Unit,
    onDetails: () -> Unit
) {
    val player = handle?.player
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(tile.durationMs) }
    LaunchedEffect(player) {
        while (isActive) {
            position = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
            duration = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: tile.durationMs
            delay(200)
        }
    }
    Box(Modifier.matchParentSize().background(Color.Black.copy(.36f))) {
        Row(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            SmallIcon(Icons.Default.Replay10, "10초 뒤") {
                val target = (player?.currentPosition?.minus(10_000) ?: 0L).coerceAtLeast(0)
                player?.seekTo(target); onUpdate { it.copy(playbackPosition = target) }; onInteract()
            }
            SmallIcon(if (player?.isPlaying == true) Icons.Default.Pause else Icons.Default.PlayArrow, "재생") {
                val play = player?.isPlaying != true
                onUpdate { it.copy(playing = play) }
                if (play) player?.play() else player?.pause()
                onInteract()
            }
            SmallIcon(Icons.Default.Forward10, "10초 앞") {
                val target = (player?.currentPosition?.plus(10_000) ?: 0L).coerceAtMost(duration)
                player?.seekTo(target); onUpdate { it.copy(playbackPosition = target) }; onInteract()
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp)) {
            Slider(
                value = position.coerceAtMost(duration).toFloat(),
                onValueChange = { value -> player?.seekTo(value.toLong()); onUpdate { it.copy(playbackPosition = value.toLong()) }; onInteract() },
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                modifier = Modifier.fillMaxWidth().height(22.dp),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(.3f))
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${formatTime(position)} / ${formatTime(duration)}", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Icon(if (tile.mute) Icons.Default.VolumeOff else Icons.Default.VolumeUp, null, Modifier.size(16.dp), tint = Color.White)
                Slider(
                    value = tile.volume,
                    onValueChange = { value -> onUpdate { it.copy(volume = value) }; onInteract() },
                    modifier = Modifier.width(92.dp).height(20.dp),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White)
                )
                Text("${"%.2f".format(tile.playbackSpeed)}×", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp))
                IconButton(onClick = { onDetails(); onInteract() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, "상세 설정", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(46.dp)) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(29.dp))
    }
}

@Composable
private fun GlobalToolbar(
    project: ProjectState,
    editMode: Boolean,
    onPickMany: () -> Unit,
    onEdit: () -> Unit,
    onLayout: (Int) -> Unit,
    onProjects: () -> Unit,
    onMute: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    var layouts by remember { mutableStateOf(false) }
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = Color(0xCC171717), tonalElevation = 0.dp) {
        Row(Modifier.height(48.dp).padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onProjects) { Icon(Icons.Default.FolderOpen, "프로젝트") }
            IconButton(onClick = onPickMany) { Icon(Icons.Default.Add, "영상 추가") }
            Box {
                IconButton(onClick = { layouts = true }) { Icon(Icons.Default.GridView, "레이아웃") }
                DropdownMenu(expanded = layouts, onDismissRequest = { layouts = false }) {
                    listOf(1, 2, 4, 6, 9, 12).filter { it >= project.tiles.size }.forEach { count ->
                        DropdownMenuItem(text = { Text("${count}분할") }, onClick = { layouts = false; onLayout(count) })
                    }
                }
            }
            IconButton(onClick = onEdit) { Icon(if (editMode) Icons.Default.Check else Icons.Default.OpenWith, "자유배치") }
            IconButton(onClick = onMute) { Icon(if (project.masterMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, "전체 음소거") }
            IconButton(onClick = onExport) { Icon(Icons.Default.Download, "영상으로 저장") }
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun viewUri(context: Context, uri: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(uri), "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    })
}

private fun shareUri(context: Context, uri: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "영상 공유"))
}

private fun browseVideos(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI).apply {
        type = "video/*"
    }
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "영상 파일은 Movies/MultiViewStudio에 저장되었습니다.", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun BackHandlerCompat(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
}
