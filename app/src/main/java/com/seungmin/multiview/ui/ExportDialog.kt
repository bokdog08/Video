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
import com.seungmin.multiview.ExportUiState
import com.seungmin.multiview.model.*
import com.seungmin.multiview.timeline.TimelineEngine

@Composable
fun ExportDialog(
    project: ProjectState,
    exportState: ExportUiState,
    onDismiss: () -> Unit,
    onPreview: (ExportSettings) -> Unit,
    onStart: (ExportSettings) -> Unit,
    onCancel: () -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    onLocate: () -> Unit
) {
    var settings by remember(project.id) { mutableStateOf(ExportSettings()) }
    var durationText by remember { mutableStateOf("00:01:00") }
    var customWidth by remember { mutableStateOf("1920") }
    var customHeight by remember { mutableStateOf("1080") }
    var resolution by remember { mutableStateOf("1080p") }
    val estimated = TimelineEngine.outputDurationMs(project, settings)

    Dialog(onDismissRequest = { if (!exportState.running) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxHeight(.94f).widthIn(min = 520.dp, max = 720.dp).fillMaxWidth(.62f),
            shape = MaterialTheme.shapes.large,
            color = Color(0xFF171717)
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
                Row(Modifier.height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("영상으로 저장", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (!exportState.running) IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "닫기") }
                }
                HorizontalDivider(color = Color.White.copy(.12f))
                if (exportState.running || exportState.outputUri != null || exportState.error != null) {
                    ExportStatus(exportState, onCancel, onOpen, onShare, onLocate, onDismiss, Modifier.weight(1f))
                } else {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        SectionTitle("영상 길이")
                        ChoiceRow(
                            choices = listOf(OutputLengthMode.LONGEST to "가장 긴 영상 기준", OutputLengthMode.CUSTOM to "직접 지정"),
                            selected = settings.lengthMode,
                            onSelected = { settings = settings.copy(lengthMode = it) }
                        )
                        if (settings.lengthMode == OutputLengthMode.CUSTOM) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(30_000L to "30초", 60_000L to "1분", 300_000L to "5분", 600_000L to "10분", 1_800_000L to "30분").forEach { (duration, label) ->
                                    AssistChip(onClick = { settings = settings.copy(customDurationMs = duration); durationText = toClock(duration) }, label = { Text(label) })
                                }
                            }
                            OutlinedTextField(
                                value = durationText,
                                onValueChange = { text -> durationText = text; parseClock(text)?.let { settings = settings.copy(customDurationMs = it) } },
                                label = { Text("HH:MM:SS") },
                                isError = parseClock(durationText) == null,
                                singleLine = true
                            )
                        }
                        Text("예상 결과 길이 ${formatMillis(estimated)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

                        SectionTitle("짧은 영상")
                        ChoiceRow(
                            choices = listOf(
                                ShortMediaMode.LOOP to "자동 반복",
                                ShortMediaMode.HOLD_LAST_FRAME to "마지막 프레임 유지",
                                ShortMediaMode.BLACK to "검은 화면"
                            ),
                            selected = settings.shortMediaMode,
                            onSelected = { settings = settings.copy(shortMediaMode = it) }
                        )

                        SectionTitle("해상도")
                        ChoiceRow(
                            choices = listOf("1080p" to "1080p", "1440p" to "1440p", "4K" to "4K", "custom" to "사용자 지정"),
                            selected = resolution,
                            onSelected = { selected ->
                                resolution = selected
                                settings = when (selected) {
                                    "1080p" -> settings.copy(width = 1920, height = 1080)
                                    "1440p" -> settings.copy(width = 2560, height = 1440)
                                    "4K" -> settings.copy(width = 3840, height = 2160)
                                    else -> settings
                                }
                            }
                        )
                        if (resolution == "custom") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(customWidth, { customWidth = it; it.toIntOrNull()?.let { w -> settings = settings.copy(width = w.coerceIn(320, 4096).let { even -> even - even % 2 }) } }, label = { Text("너비") }, modifier = Modifier.weight(1f), singleLine = true)
                                OutlinedTextField(customHeight, { customHeight = it; it.toIntOrNull()?.let { h -> settings = settings.copy(height = h.coerceIn(240, 4096).let { even -> even - even % 2 }) } }, label = { Text("높이") }, modifier = Modifier.weight(1f), singleLine = true)
                            }
                        }

                        SectionTitle("FPS")
                        ChoiceRow(
                            choices = listOf(0 to "원본 기반", 30 to "30", 60 to "60"),
                            selected = if (settings.useSourceFps) 0 else settings.fps,
                            onSelected = { fps -> settings = settings.copy(useSourceFps = fps == 0, fps = if (fps == 0) project.tiles.maxOfOrNull { it.sourceFps.toInt() }?.coerceIn(1, 120) ?: 30 else fps) }
                        )

                        SectionTitle("코덱")
                        ChoiceRow(
                            choices = listOf(VideoCodec.H264 to "H.264", VideoCodec.HEVC to "HEVC / H.265"),
                            selected = settings.codec,
                            onSelected = { settings = settings.copy(codec = it) }
                        )

                        SectionTitle("오디오")
                        ChoiceRow(
                            choices = listOf(AudioExportMode.ALL_ACTIVE to "모든 활성 영상 믹싱", AudioExportMode.SELECTED_ONLY to "선택 영상만"),
                            selected = settings.audioMode,
                            onSelected = { settings = settings.copy(audioMode = it) }
                        )
                        Text("영상별 Volume · Mute · Audio Pan · 배속과 ‘오디오 1회’ 설정이 그대로 반영됩니다.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(.62f))
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { onPreview(settings.withResolvedFps(project)) }, modifier = Modifier.weight(1f)) { Text("최종 결과 미리보기") }
                        Button(onClick = { onStart(settings.withResolvedFps(project)) }, modifier = Modifier.weight(1f)) { Text("영상 제작 시작") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportStatus(
    state: ExportUiState,
    onCancel: () -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    onLocate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        when {
            state.running -> {
                Text("영상 제작 중", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(18.dp))
                LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth(.76f).height(10.dp))
                Spacer(Modifier.height(10.dp))
                Text("${state.progress}%")
                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = onCancel) { Text("취소") }
            }
            state.outputUri != null -> {
                Text("영상 제작 완료", style = MaterialTheme.typography.headlineSmall)
                Text(state.outputName.orEmpty(), color = Color.White.copy(.65f))
                Spacer(Modifier.height(20.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onOpen(state.outputUri) }) { Text("재생") }
                    OutlinedButton(onClick = { onShare(state.outputUri) }) { Text("공유") }
                    OutlinedButton(onClick = onLocate) { Text("파일 위치") }
                    TextButton(onClick = onDismiss) { Text("닫기") }
                }
            }
            else -> {
                Text("영상 제작 실패", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
                Text(state.error.orEmpty(), modifier = Modifier.padding(22.dp))
                Button(onClick = onDismiss) { Text("닫기") }
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(choices: List<Pair<T, String>>, selected: T, onSelected: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        choices.forEach { (value, label) -> FilterChip(selected == value, { onSelected(value) }, { Text(label) }) }
    }
}

private fun parseClock(value: String): Long? {
    val parts = value.trim().split(':')
    if (parts.size != 3) return null
    val h = parts[0].toLongOrNull() ?: return null
    val m = parts[1].toLongOrNull()?.takeIf { it in 0..59 } ?: return null
    val s = parts[2].toLongOrNull()?.takeIf { it in 0..59 } ?: return null
    return ((h * 3600 + m * 60 + s) * 1000).takeIf { it in 100..43_200_000 }
}

private fun toClock(ms: Long): String {
    val seconds = ms / 1000
    return "%02d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
}

private fun ExportSettings.withResolvedFps(project: ProjectState): ExportSettings = if (useSourceFps) {
    copy(fps = project.tiles.maxOfOrNull { it.sourceFps.toInt() }?.coerceIn(1, 120) ?: 30)
} else this
