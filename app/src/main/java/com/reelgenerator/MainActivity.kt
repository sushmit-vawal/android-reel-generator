package com.reelgenerator

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import java.text.DateFormat
import java.util.Date

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFBEF264), background = Color(0xFF101410), surface = Color(0xFF1C241C))) {
                val model: ReelViewModel = viewModel()
                var screen by rememberSaveable { mutableStateOf("home") }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) model.addFolder(uri) }
                val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.generate() }
                fun generate() {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else model.generate()
                    screen = "home"
                }
                androidx.activity.compose.BackHandler(screen != "home") { screen = "home" }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Spacer(Modifier.height(12.dp))
                        Text(if (screen == "folders") "SOURCE FOLDERS" else if (screen == "reels") "YOUR REELS" else "REEL GENERATOR", style = MaterialTheme.typography.headlineLarge)
                        if (screen != "home") TextButton(onClick = { screen = "home" }) { Text("Back") }
                        when (screen) {
                            "folders" -> {
                                Text("Select each folder once. Videos in subfolders are included.")
                                Button(onClick = { picker.launch(null) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text("Add Source Folder") }
                                OutlinedButton(onClick = model::rescan, enabled = !model.busy && model.folders.isNotEmpty()) { Text("Rescan Folders") }
                                model.folders.forEach { folder ->
                                    Card(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(folder.name, style = MaterialTheme.typography.titleMedium)
                                            Text("${folder.videoCount} videos • ${if (folder.enabled) "Enabled" else "Disabled"}")
                                            Text(if (folder.lastScanned == 0L) "Not scanned yet" else "Scanned ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(folder.lastScanned))}", style = MaterialTheme.typography.bodySmall)
                                            folder.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Switch(checked = folder.enabled, onCheckedChange = { model.enable(folder, it) }, enabled = !model.busy)
                                                TextButton(onClick = { model.remove(folder) }, enabled = !model.busy) { Text("Remove") }
                                            }
                                        }
                                    }
                                }
                                Text("Removing a folder does not delete its videos.", style = MaterialTheme.typography.bodySmall)
                            }
                            "reels" -> {
                                if (model.reels.isEmpty()) Text("Completed reels will appear here.")
                                model.reels.forEach { reel ->
                                    Card(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("Reel ${reel.slot + 1}", style = MaterialTheme.typography.titleMedium)
                                            Text(reel.caption)
                                            Row {
                                                TextButton(onClick = { openVideo(Uri.parse(reel.outputUri), model) }) { Text("Play") }
                                                TextButton(onClick = { shareVideos(listOf(Uri.parse(reel.outputUri)), model) }) { Text("Share") }
                                            }
                                        }
                                    }
                                }
                                if (model.reels.isNotEmpty()) OutlinedButton(onClick = { shareVideos(model.reels.map { Uri.parse(it.outputUri) }, model) }) { Text("Share All") }
                            }
                            else -> {
                                Text("Your videos. Five moments to share.", style = MaterialTheme.typography.bodyLarge)
                                Text("SOURCE FOLDERS", style = MaterialTheme.typography.labelLarge)
                                if (model.folders.isEmpty()) Text("Choose one or more folders to get started.")
                                else model.folders.forEach { Text("${it.name}${if (it.enabled) "" else " (disabled)"}") }
                                OutlinedButton(onClick = { screen = "folders" }, modifier = Modifier.fillMaxWidth()) { Text("Select / Manage Folders") }
                                Text("REEL TYPE", style = MaterialTheme.typography.labelLarge)
                                Choice(model.category.label, ReelCategory.entries.map { it.label }, !model.busy) { label -> model.choose(ReelCategory.entries.first { it.label == label }) }
                                if (model.category == ReelCategory.HUMOR) {
                                    Text("HUMOR STYLE", style = MaterialTheme.typography.labelLarge)
                                    Choice(model.humor.label, HumorStyle.entries.map { it.label }, !model.busy) { label -> model.chooseHumor(HumorStyle.entries.first { it.label == label }) }
                                }
                                Button(onClick = { generate() }, enabled = !model.busy && model.folders.any { it.enabled }, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                                    Text(if (model.workActive) "CREATING YOUR REELS" else "GENERATE")
                                }
                                Text("Creates 5 reels", style = MaterialTheme.typography.labelMedium)
                                if (model.workActive) {
                                    LinearProgressIndicator(progress = { model.reels.size / 5f }, modifier = Modifier.fillMaxWidth())
                                    Text("You can leave this screen. Android may pause work to protect battery or system resources.")
                                    TextButton(onClick = model::cancel) { Text("Cancel generation") }
                                }
                                model.batch?.let { Text(it.message) }
                                if (model.reels.isNotEmpty()) OutlinedButton(onClick = { screen = "reels" }, modifier = Modifier.fillMaxWidth()) { Text("View ${model.reels.size} Reels") }
                                Text("Your videos stay on your phone. Phase 2 uses preset text; choose footage that suits your category.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (model.loading || model.managing) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (model.message.isNotBlank()) Text(model.message)
                    }
                }
            }
        }
    }
    private fun openVideo(uri: Uri, model: ReelViewModel) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            .onFailure { model.report("Could not open this reel. Check Gallery → Upload Reels; it may have been deleted.") }
    }
    private fun shareVideos(uris: List<Uri>, model: ReelViewModel) {
        if (uris.isEmpty()) return
        val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).setType("video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.size == 1) intent.putExtra(Intent.EXTRA_STREAM, uris.first()) else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        intent.clipData = ClipData.newUri(contentResolver, "Reel", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        runCatching { startActivity(Intent.createChooser(intent, "Share reels")) }.onFailure { model.report("No sharing app is available.") }
    }
}

@Composable
private fun Choice(value: String, options: List<String>, enabled: Boolean, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("$value ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { select(option); expanded = false }) }
        }
    }
}
