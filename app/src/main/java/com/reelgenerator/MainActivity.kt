package com.reelgenerator

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFBEF264), background = Color(0xFF101410), surface = Color(0xFF1C241C))) {
                val model: ReelViewModel = viewModel()
                DisposableEffect(model.busy) {
                    if (model.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        try {
                            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            val previous = model.source
                            model.select(uri)
                            if (previous != null && previous != uri) runCatching {
                                contentResolver.releasePersistableUriPermission(previous, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        } catch (_: SecurityException) { model.report("Access could not be saved. Choose a video from device storage.") }
                    }
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Spacer(Modifier.height(24.dp))
                        Text("REEL GENERATOR", style = MaterialTheme.typography.headlineLarge)
                        Text("A moment from your phone.\nA reel ready to share.", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(12.dp))
                        Text("SOURCE VIDEO", style = MaterialTheme.typography.labelLarge)
                        OutlinedButton(onClick = { picker.launch(arrayOf("video/*")) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (model.source == null) "Select Source Video" else "Change Selected Video")
                        }
                        if (model.source != null) Text("Video selected", color = MaterialTheme.colorScheme.primary)
                        Text("REEL TYPE", style = MaterialTheme.typography.labelLarge)
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { expanded = true }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text("${model.category.label} ▾") }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                ReelCategory.entries.forEach { category ->
                                    DropdownMenuItem(text = { Text(category.label) }, onClick = { model.choose(category); expanded = false })
                                }
                            }
                        }
                        Button(onClick = model::generate, enabled = model.source != null && !model.busy, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                            Text(if (model.busy) "CREATING YOUR REEL" else "GENERATE")
                        }
                        Text("Creates 1 reel • Phase 1", style = MaterialTheme.typography.labelMedium)
                        if (model.busy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("Keep this screen open while rendering.")
                            TextButton(onClick = model::cancel) { Text("Cancel") }
                        }
                        if (model.message.isNotBlank()) Text(model.message)
                        model.output?.let { uri ->
                            Button(onClick = {
                                runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                                    .onFailure { model.report("Open your Gallery and look in Upload Reels.") }
                            }, modifier = Modifier.fillMaxWidth()) { Text("View Reel") }
                            OutlinedButton(onClick = {
                                runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share reel")) }
                                    .onFailure { model.report("No sharing app is available. Your reel is saved in Upload Reels.") }
                            }, modifier = Modifier.fillMaxWidth()) { Text("Share") }
                        }
                        Text("Your video stays on this device. Add music in Instagram after reviewing your reel.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
