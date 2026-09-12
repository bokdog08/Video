@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.media3.common.util.UnstableApi::class
)

package com.seungmin.multiview.ui

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.seungmin.multiview.model.HighlightPlayMode
import com.seungmin.multiview.model.HighlightSegment
import com.seungmin.multiview.model.RepeatMode
import com.seungmin.multiview.model.VideoTileState
import com.seungmin.multiview.playback.PlayerManager
import com.seungmin.multiview.timeline.TimelineEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToLong

@Composable
fun HighlightEditorDialog(
    tile: VideoTileState,
    playerManager: PlayerManager,
    onDismiss: () -> Unit,
    onSave: (VideoTileState) -> Unit
) {
    val context = LocalContext.current
    var working by remember(tile.id) { mutableStateOf(tile) }
    var position by remember { mutableLongStateOf(tile.playbackPosition.coerceIn(0L, tile.durationMs)) }
    var markIn by remember { mutableLongStateOf(position) }
    var markOut by remember { mutableLongStateOf((position + 5_000L).coerceAtMost(tile.durationMs)) }
    var cutPoint by remember { mutableStateOf<Long?>(null) }
    var selectedId by remember { mutableStateOf<String?>(working.highlightSegments.firstOrNull()?.id) }
    var adjustEnd by remember { mutableStateOf(false) }
    val frameMs = (1_000f / tile.sourceFps.coerceAtLeast(1f)).roundToLong().coerceAtLeast(1L)
    val editorPlayer = remember(tile.uri) { ExoPlayer.Builder(context).build() }
    val livePlayer = remember(tile.id) { playerManager.get(tile.id)?.player }

    DisposableEffect(editorPlayer, tile.uri) {
        val resumeLive = livePlayer?.isPlaying == true
        livePlayer?.pause()
        editorPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(tile.uri)), tile.playbackPosition)
        editorPlayer.prepare()
        onDispose {
            editorPlayer.release()
            if (resumeLive) livePlayer?.play()
        }
    }
    LaunchedEffect(editorPlayer) {
        while (isActive) {
            position = editorPlayer.currentPosition.coerceIn(0L, tile.durationMs)
            delay(50)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF080808)) {
            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "닫기") }
                    Text("하이라이트 편집", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val normalized = working.highlightSegments.mapIndexed { index, segment -> segment.copy(order = index) }
                        onSave(working.copy(highlightSegments = normalized, repeatMode = RepeatMode.HIGHLIGHT, playbackPosition = position))
                    }) { Text("저장") }
                }

                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1.25f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AndroidView(
                            factory = { PlayerView(it).apply { useController = false; player = editorPlayer } },
                            update = { it.player = editorPlayer },
                            modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)
                        )
                        HighlightTimeline(
                            durationMs = tile.durationMs,
                            positionMs = position,
                            segments = working.highlightSegments,
                            markIn = markIn,
                            markOut = markOut,
                            onSeek = editorPlayer::seekTo,
                            onBoundaryChange = { id, end, value ->
                                working = working.copy(highlightSegments = working.highlightSegments.map { segment ->
                                    if (segment.id != id) segment
                                    else if (end) segment.copy(endMs = value.coerceIn(segment.startMs + 1L, tile.durationMs))
                                    else segment.copy(startMs = value.coerceIn(0L, segment.endMs - 1L))
                                })
                            },
                            modifier = Modifier.fillMaxWidth().height(62.dp)
                        )
                        Slider(
                            value = position.toFloat(),
                            onValueChange = { editorPlayer.seekTo(it.toLong()) },
                            valueRange = 0f..tile.durationMs.coerceAtLeast(1L).toFloat(),
                            modifier = Modifier.fillMaxWidth().height(24.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(onClick = { if (editorPlayer.isPlaying) editorPlayer.pause() else editorPlayer.play() }) {
                                Icon(if (editorPlayer.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "재생")
                            }
                            Text(formatMillis(position), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(112.dp))
                            OutlinedButton(onClick = { markIn = position.coerceAtMost(markOut - 1L) }) { Text("시작점") }
                            OutlinedButton(onClick = { markOut = position.coerceAtLeast(markIn + 1L) }) { Text("끝점") }
                            Button(onClick = {
                                if (markOut > markIn) {
                                    val segment = HighlightSegment(startMs = markIn, endMs = markOut, order = working.highlightSegments.size, name = "구간 ${working.highlightSegments.size + 1}")
                                    working = working.copy(highlightSegments = working.highlightSegments + segment)
                                    selectedId = segment.id
                                }
                            }) { Icon(Icons.Default.Add, null); Text("구간") }
                            FilledTonalButton(onClick = {
                                val first = cutPoint
                                if (first == null) cutPoint = position
                                else {
                                    val start = minOf(first, position)
                                    val end = maxOf(first, position)
                                    if (end > start) {
                                        val segment = HighlightSegment(startMs = start, endMs = end, order = working.highlightSegments.size, name = "Cut ${working.highlightSegments.size + 1}")
                                        working = working.copy(highlightSegments = working.highlightSegments + segment)
                                        selectedId = segment.id
                                    }
                                    cutPoint = null
                                }
                            }) {
                                Icon(Icons.Default.ContentCut, null)
                                Text(if (cutPoint == null) "가위" else "가위 완료")
                            }
                        }
                        cutPoint?.let { Text("첫 Cut Point: ${formatMillis(it)} · 두 번째 위치에서 가위를 누르세요.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) }
                    }

                    Column(Modifier.weight(.9f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HighlightPlayMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = working.highlightPlayMode == mode,
                                    onClick = { working = working.copy(highlightPlayMode = mode) },
                                    label = { Text(when (mode) {
                                        HighlightPlayMode.SEQUENTIAL -> "순차"
                                        HighlightPlayMode.PER_SEGMENT_REPEAT -> "구간별 반복"
                                        HighlightPlayMode.RANDOM -> "랜덤"
                                    }) }
                                )
                            }
                        }
                        if (working.highlightPlayMode == HighlightPlayMode.RANDOM) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("같은 구간 연속 방지", modifier = Modifier.weight(1f))
                                Switch(working.avoidSameRandomSegment, { working = working.copy(avoidSameRandomSegment = it) })
                            }
                        }
                        Text(
                            "가상 영상 길이 ${formatMillis(TimelineEngine.cycleDurationMs(working.copy(repeatMode = RepeatMode.HIGHLIGHT)))}",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge
                        )
                        val selected = working.highlightSegments.firstOrNull { it.id == selectedId }
                        if (selected != null) {
                            PrecisionEditor(
                                segment = selected,
                                adjustEnd = adjustEnd,
                                frameMs = frameMs,
                                durationMs = tile.durationMs,
                                onSelectHandle = { adjustEnd = it },
                                onNudge = { delta ->
                                    working = working.copy(highlightSegments = working.highlightSegments.map { segment ->
                                        if (segment.id != selected.id) segment
                                        else if (adjustEnd) segment.copy(endMs = (segment.endMs + delta).coerceIn(segment.startMs + 1L, tile.durationMs))
                                        else segment.copy(startMs = (segment.startMs + delta).coerceIn(0L, segment.endMs - 1L))
                                    })
                                }
                            )
                        }
                        Text("구간 · 길게 끌어 순서 변경", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(.65f))
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(working.highlightSegments, key = { it.id }) { segment ->
                                SegmentRow(
                                    segment = segment,
                                    selected = selectedId == segment.id,
                                    onSelect = { selectedId = segment.id; editorPlayer.seekTo(segment.startMs) },
                                    onUpdate = { updated -> working = working.copy(highlightSegments = working.highlightSegments.map { if (it.id == updated.id) updated else it }) },
                                    onDelete = {
                                        working = working.copy(highlightSegments = working.highlightSegments.filterNot { it.id == segment.id }.mapIndexed { i, s -> s.copy(order = i) })
                                        if (selectedId == segment.id) selectedId = working.highlightSegments.firstOrNull()?.id
                                    },
                                    onMove = { direction -> working = working.copy(highlightSegments = moveSegment(working.highlightSegments, segment.id, direction)) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HighlightTimeline(
    durationMs: Long,
    positionMs: Long,
    segments: List<HighlightSegment>,
    markIn: Long,
    markOut: Long,
    onSeek: (Long) -> Unit,
    onBoundaryChange: (segmentId: String, end: Boolean, valueMs: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier
            .background(Color(0xFF151515), RoundedCornerShape(6.dp))
            .pointerInput(durationMs) {
                detectTapGestures { point -> onSeek((point.x / size.width.coerceAtLeast(1) * durationMs).toLong()) }
            }
            .pointerInput(durationMs, segments) {
                var boundary: Pair<String, Boolean>? = null
                detectDragGestures(
                    onDragStart = { point ->
                        boundary = segments.flatMap { segment ->
                            listOf(
                                Triple(segment.id, false, kotlin.math.abs(point.x - segment.startMs.toFloat() / durationMs.coerceAtLeast(1L) * size.width)),
                                Triple(segment.id, true, kotlin.math.abs(point.x - segment.endMs.toFloat() / durationMs.coerceAtLeast(1L) * size.width))
                            )
                        }.minByOrNull { it.third }?.let { it.first to it.second }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        boundary?.let { (id, end) ->
                            val time = (change.position.x / size.width.coerceAtLeast(1) * durationMs).toLong().coerceIn(0L, durationMs)
                            onBoundaryChange(id, end, time)
                        }
                    },
                    onDragEnd = { boundary = null },
                    onDragCancel = { boundary = null }
                )
            }
    ) {
        val duration = durationMs.coerceAtLeast(1L).toFloat()
        drawRect(Color(0xFF252525), topLeft = Offset(0f, size.height * .34f), size = androidx.compose.ui.geometry.Size(size.width, size.height * .32f))
        segments.forEachIndexed { index, segment ->
            val left = segment.startMs / duration * size.width
            val right = segment.endMs / duration * size.width
            drawRect(if (segment.enabled) Color(0xFF00C2FF).copy(.72f) else Color.Gray.copy(.45f), Offset(left, 5f + index % 2 * 10f), androidx.compose.ui.geometry.Size((right - left).coerceAtLeast(2f), size.height - 10f - index % 2 * 10f))
        }
        val inX = markIn / duration * size.width
        val outX = markOut / duration * size.width
        drawLine(Color(0xFF4CAF50), Offset(inX, 0f), Offset(inX, size.height), 2f)
        drawLine(Color(0xFFFF9800), Offset(outX, 0f), Offset(outX, size.height), 2f)
        val playX = positionMs / duration * size.width
        drawLine(Color.White, Offset(playX, 0f), Offset(playX, size.height), 3f)
    }
}

@Composable
private fun PrecisionEditor(
    segment: HighlightSegment,
    adjustEnd: Boolean,
    frameMs: Long,
    durationMs: Long,
    onSelectHandle: (Boolean) -> Unit,
    onNudge: (Long) -> Unit
) {
    Surface(color = Color.White.copy(.06f), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(!adjustEnd, { onSelectHandle(false) }, { Text("시작 ${formatMillis(segment.startMs)}") })
                FilterChip(adjustEnd, { onSelectHandle(true) }, { Text("끝 ${formatMillis(segment.endMs)}") })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(-1_000L to "−1s", -100L to "−0.1s", -frameMs to "−1f", frameMs to "+1f", 100L to "+0.1s", 1_000L to "+1s").forEach { (delta, label) ->
                    OutlinedButton(onClick = { onNudge(delta) }, contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp)) { Text(label) }
                }
            }
            Text("프레임 간격 약 ${frameMs}ms · 영상 길이 ${formatMillis(durationMs)}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(.55f))
        }
    }
}

@Composable
private fun SegmentRow(
    segment: HighlightSegment,
    selected: Boolean,
    onSelect: () -> Unit,
    onUpdate: (HighlightSegment) -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit
) {
    var dragY by remember(segment.id) { mutableFloatStateOf(0f) }
    Surface(
        onClick = onSelect,
        color = if (selected) MaterialTheme.colorScheme.primary.copy(.18f) else Color.White.copy(.055f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(segment.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragY = 0f },
                    onDrag = { change, amount -> change.consume(); dragY += amount.y },
                    onDragEnd = { if (kotlin.math.abs(dragY) > 24f) onMove(if (dragY > 0) 1 else -1); dragY = 0f },
                    onDragCancel = { dragY = 0f }
                )
            }
    ) {
        Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DragHandle, null, Modifier.size(18.dp), tint = Color.White.copy(.55f))
                Text("${segment.order + 1}. ${segment.name}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Switch(segment.enabled, { onUpdate(segment.copy(enabled = it)) }, modifier = Modifier.height(28.dp))
                IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.DeleteOutline, "삭제", Modifier.size(18.dp)) }
            }
            OutlinedTextField(
                value = segment.name,
                onValueChange = { onUpdate(segment.copy(name = it.take(40))) },
                label = { Text("구간 이름") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text("${formatMillis(segment.startMs)}  →  ${formatMillis(segment.endMs)}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(.68f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("반복 ${segment.repeatCount}회", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { onUpdate(segment.copy(repeatCount = (segment.repeatCount - 1).coerceAtLeast(1))) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Remove, "감소", Modifier.size(16.dp)) }
                IconButton(onClick = { onUpdate(segment.copy(repeatCount = (segment.repeatCount + 1).coerceAtMost(999))) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Add, "증가", Modifier.size(16.dp)) }
            }
        }
    }
}

private fun moveSegment(items: List<HighlightSegment>, id: String, direction: Int): List<HighlightSegment> {
    val mutable = items.toMutableList()
    val from = mutable.indexOfFirst { it.id == id }
    if (from < 0) return items
    val to = (from + direction).coerceIn(0, mutable.lastIndex)
    if (from != to) mutable.add(to, mutable.removeAt(from))
    return mutable.mapIndexed { index, item -> item.copy(order = index) }
}
