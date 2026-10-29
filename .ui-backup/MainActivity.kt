package com.note.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.note.app.data.Note
import com.note.app.data.NoteDatabase
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NoteHome() }
    }
}

@Composable
private fun NoteHome() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dao = remember { NoteDatabase.get(context).noteDao() }
    val scope = rememberCoroutineScope()
    val notes by dao.observeAll().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var showEditor by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    MaterialTheme(colorScheme = lightColorScheme(
        background = Color(0xFFFAF9F6),
        surface = Color(0xFFFAF9F6),
        primary = Color(0xFF596044)
    )) {
        Scaffold(
            containerColor = Color(0xFFFAF9F6),
            floatingActionButton = {
                FloatingActionButton(onClick = {
                    title = ""; content = ""; showEditor = true
                }) { Icon(Icons.Outlined.Add, contentDescription = "New note") }
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(20.dp)
            ) {
                Text("Note", fontSize = 32.sp)
                Text("Your thoughts, in one place", color = Color.Gray)
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search notes") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true
                )
                Spacer(Modifier.height(16.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(notes.filter {
                        it.title.contains(query, true) ||
                            it.content.contains(query, true)
                    }, key = { it.id }) { note ->
                        Column(
                            Modifier.fillMaxWidth()
                                .background(Color(note.color.takeIf { it != 0 } ?: -1),
                                    RoundedCornerShape(16.dp))
                                .clickable {
                                    title = note.title
                                    content = note.content
                                    showEditor = true
                                }.padding(16.dp)
                        ) {
                            Text(note.title.ifBlank { "Untitled" }, fontSize = 18.sp)
                            if (note.content.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(note.content)
                            }
                        }
                    }
                }
            }
        }

        if (showEditor) {
            AlertDialog(
                onDismissRequest = { showEditor = false },
                title = { Text("New note") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(title, { title = it }, label = { Text("Title") })
                        OutlinedTextField(content, { content = it },
                            label = { Text("Write your note") })
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (title.isNotBlank() || content.isNotBlank()) {
                            scope.launch {
                                dao.insert(Note(title = title.trim(), content = content.trim()))
                            }
                        }
                        showEditor = false
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showEditor = false }) { Text("Cancel") }
                }
            )
        }
    }
}
