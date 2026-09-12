@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.seungmin.multiview.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.seungmin.multiview.model.*
import kotlin.math.roundToInt

@Composable
fun TileSettingsDialog(
    tile: VideoTileState,
    onDismiss: () -> Unit,
    onUpdate: ((VideoTileState) -> VideoTileState) -> Unit,
    onHighlight: () -> Unit,
    onSolo: () -> Unit,
    onReplace: (Boolean) -> Unit,
    onFullscreen: () -> Unit,
    onLayer: (delta: Int, absolute: Boolean) -> Unit,
    onRemove: () -> Unit
) {
    var confirmRemove by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                Modifier.fillMaxHeight().widthIn(min = 340.dp, max = 440.dp).fillMaxWidth(.43f),
                color = Color(0xF5181818),
                tonalElevation = 0.dp
            ) {
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                    Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tile.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "닫기") }
                    }
                    HorizontalDivider(color = Color.White.copy(.12f))
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        SectionTitle("재생")
                        Text("배속  ${"%.2f".format(tile.playbackSpeed)}×", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(.25f, .5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f).forEach { speed ->
                                FilterChip(
                                    selected = tile.playbackSpeed == speed,
                                    onClick = { onUpdate { it.copy(playbackSpeed = speed) } },
                                    label = { Text("${speed}×") }
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { onUpdate { it.copy(playbackSpeed = it.playbackSpeed - .05f) } }) { Text("−0.05") }
                            OutlinedButton(onClick = { onUpdate { it.copy(playbackSpeed = it.playbackSpeed + .05f) } }) { Text("+0.05") }
                            Text("피치 유지", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(.6f))
                        }

                        SectionTitle("오디오")
                        SettingSlider(
                            label = "볼륨 ${(tile.volume * 100).roundToInt()}%",
                            value = tile.volume,
                            range = 0f..1f,
                            onChange = { value -> onUpdate { it.copy(volume = value) } }
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("음소거", modifier = Modifier.weight(1f))
                            Switch(checked = tile.mute, onCheckedChange = { mute -> onUpdate { it.copy(mute = mute) } })
                        }
                        OutlinedButton(onClick = onSolo, modifier = Modifier.fillMaxWidth()) { Text("이 영상만 소리 켜기") }
                        SettingSlider(
                            label = "Audio Pan  ${(tile.audioPan * 100).roundToInt()}",
                            value = tile.audioPan,
                            range = -1f..1f,
                            onChange = { value -> onUpdate { it.copy(audioPan = value) } }
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("L" to -1f, "L50" to -.5f, "Center" to 0f, "R50" to .5f, "R" to 1f).forEach { (label, pan) ->
                                AssistChip(onClick = { onUpdate { it.copy(audioPan = pan) } }, label = { Text(label) })
                            }
                        }
                        EnumRow("소스", SourceMode.entries, tile.sourceMode, { it.name.replace('_', ' ') }) { mode ->
                            onUpdate { it.copy(sourceMode = mode) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("선택 오디오 Export에 포함", modifier = Modifier.weight(1f))
                            Switch(tile.audioSelectedForExport, { selected -> onUpdate { it.copy(audioSelectedForExport = selected) } })
                        }

                        SectionTitle("반복")
                        EnumRow("방식", RepeatMode.entries, tile.repeatMode, {
                            when (it) { RepeatMode.NONE -> "없음"; RepeatMode.FULL -> "전체"; RepeatMode.AB -> "A–B"; RepeatMode.HIGHLIGHT -> "하이라이트" }
                        }) { mode -> onUpdate { it.copy(repeatMode = mode) } }
                        if (tile.repeatMode == RepeatMode.AB) {
                            val duration = tile.durationMs.coerceAtLeast(1L)
                            SettingSlider("A  ${formatMillis(tile.loopStart)}", tile.loopStart.toFloat(), 0f..duration.toFloat()) { value ->
                                onUpdate { it.copy(loopStart = value.toLong().coerceAtMost(it.loopEnd - 1L)) }
                            }
                            SettingSlider("B  ${formatMillis(tile.loopEnd)}", tile.loopEnd.toFloat(), 0f..duration.toFloat()) { value ->
                                onUpdate { it.copy(loopEnd = value.toLong().coerceAtLeast(it.loopStart + 1L)) }
                            }
                        }
                        Button(onClick = onHighlight, modifier = Modifier.fillMaxWidth()) {
                            Text("하이라이트 다중 구간 편집 (${tile.highlightSegments.size})")
                        }
                        EnumRow("반복 시 오디오", RepeatAudioMode.entries, tile.repeatAudioMode, {
                            if (it == RepeatAudioMode.WITH_VIDEO) "영상과 함께 반복" else "오디오는 1회"
                        }) { mode -> onUpdate { it.copy(repeatAudioMode = mode) } }

                        SectionTitle("화면")
                        EnumRow("맞춤", ScaleMode.entries, tile.scaleMode, { it.name }) { mode -> onUpdate { it.copy(scaleMode = mode) } }
                        Text("영상 확대  ${"%.2f".format(tile.contentScale)}×")
                        Slider(value = tile.contentScale, onValueChange = { scale -> onUpdate { it.copy(contentScale = scale) } }, valueRange = 1f..8f)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onUpdate { it.copy(contentScale = 1f, contentOffsetX = 0f, contentOffsetY = 0f) } }) { Text("확대 초기화") }
                            OutlinedButton(onClick = { onUpdate { it.copy(contentOffsetX = 0f, contentOffsetY = 0f) } }) { Text("영상 위치 초기화") }
                        }
                        OutlinedButton(onClick = { onUpdate { it.copy(tileWidth = .5f, tileHeight = .5f) } }) { Text("Tile 크기 초기화") }

                        SectionTitle("배치")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistChip(onClick = { onLayer(1, true) }, label = { Text("맨 앞으로") })
                            AssistChip(onClick = { onLayer(1, false) }, label = { Text("한 단계 앞으로") })
                            AssistChip(onClick = { onLayer(-1, false) }, label = { Text("한 단계 뒤로") })
                            AssistChip(onClick = { onLayer(-1, true) }, label = { Text("맨 뒤로") })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Tile 잠금", modifier = Modifier.weight(1f))
                            Switch(checked = tile.locked, onCheckedChange = { locked -> onUpdate { it.copy(locked = locked) } })
                        }
                        OutlinedButton(onClick = onFullscreen, modifier = Modifier.fillMaxWidth()) { Text("이 영상 전체화면") }

                        SectionTitle("파일")
                        Button(onClick = { onReplace(true) }, modifier = Modifier.fillMaxWidth()) { Text("영상 교체 · 설정 유지") }
                        OutlinedButton(onClick = { onReplace(false) }, modifier = Modifier.fillMaxWidth()) { Text("영상 교체 · 설정 초기화") }
                        TextButton(onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Text("영상 제거")
                        }
                    }
                }
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("영상을 제거할까요?") },
            text = { Text("프로젝트에서만 제거되며 원본 파일은 삭제되지 않습니다.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; onRemove(); onDismiss() }) { Text("제거") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("취소") } }
        )
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun <T> EnumRow(label: String, values: Iterable<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            values.forEach { value ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(text(value)) })
            }
        }
    }
}

internal fun formatMillis(ms: Long): String {
    val total = ms.coerceAtLeast(0L)
    val h = total / 3_600_000
    val m = total % 3_600_000 / 60_000
    val s = total % 60_000 / 1_000
    val millis = total % 1_000
    return if (h > 0) "%02d:%02d:%02d.%03d".format(h, m, s, millis) else "%02d:%02d.%03d".format(m, s, millis)
}
