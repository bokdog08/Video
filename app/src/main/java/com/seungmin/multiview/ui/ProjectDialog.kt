package com.seungmin.multiview.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.seungmin.multiview.model.ProjectState
import java.text.DateFormat
import java.util.Date

@Composable
fun ProjectDialog(
    current: ProjectState,
    projects: List<ProjectState>,
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var name by remember(current.id) { mutableStateOf(current.name) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            Modifier.fillMaxWidth().fillMaxHeight(.82f),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF171717)
        ) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("프로젝트", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "닫기") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(name, { name = it }, label = { Text("현재 프로젝트 이름") }, modifier = Modifier.weight(1f), singleLine = true)
                    Button(onClick = { onRename(name) }) { Text("이름 저장") }
                    OutlinedButton(onClick = onNew) { Text("새 프로젝트") }
                }
                HorizontalDivider(color = Color.White.copy(.12f))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(projects, key = { it.id }) { project ->
                        Surface(
                            onClick = { onOpen(project.id) },
                            color = if (project.id == current.id) MaterialTheme.colorScheme.primary.copy(.18f) else Color.White.copy(.05f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(project.name, style = MaterialTheme.typography.titleSmall)
                                    Text("영상 ${project.tiles.size}개 · ${DateFormat.getDateTimeInstance().format(Date(project.updatedAt))}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(.55f))
                                }
                                IconButton(onClick = { onDelete(project.id) }) { Icon(Icons.Default.DeleteOutline, "삭제") }
                            }
                        }
                    }
                }
            }
        }
    }
}
