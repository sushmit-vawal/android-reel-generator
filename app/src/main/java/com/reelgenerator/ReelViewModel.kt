package com.reelgenerator

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.*

@UnstableApi
class ReelViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    var source by mutableStateOf(saved.get<String>("source")?.let(Uri::parse)); private set
    var category by mutableStateOf(ReelCategory.valueOf(saved["category"] ?: "TRAVEL")); private set
    var output by mutableStateOf(saved.get<String>("output")?.let(Uri::parse)); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf(if (saved.get<Boolean>("running") == true) "Rendering was interrupted. Please generate again." else ""); private set
    private var job: Job? = null
    fun select(uri: Uri) { source = uri; saved["source"] = uri.toString(); message = "" }
    fun choose(value: ReelCategory) { category = value; saved["category"] = value.name }
    fun report(value: String) { message = value }
    fun generate() {
        val uri = source ?: return
        if (busy) return
        busy = true
        saved["running"] = true
        output = null
        saved["output"] = null
        job = viewModelScope.launch {
            try {
                val result = ReelExporter(getApplication()).export(uri, category) { message = it }
                output = result
                saved["output"] = result.toString()
                message = "1 reel created • Saved to Movies/Upload Reels"
            } catch (cancelled: CancellationException) {
                message = "Generation cancelled."
                throw cancelled
            } catch (error: Exception) {
                message = "Could not create reel. ${error.localizedMessage ?: "Choose another video and try again."}"
            } finally { busy = false; saved["running"] = false }
        }
    }
    fun cancel() { job?.cancel() }
}
