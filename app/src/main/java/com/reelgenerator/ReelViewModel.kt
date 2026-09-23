package com.reelgenerator

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.work.*
import com.reelgenerator.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@UnstableApi
class ReelViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = ReelDatabase.get(application).dao()
    private val work = WorkManager.getInstance(application)
    var folders by mutableStateOf<List<SourceFolder>>(emptyList()); private set
    var category by mutableStateOf(ReelCategory.TRAVEL); private set
    var humor by mutableStateOf(HumorStyle.AUTO); private set
    var batch by mutableStateOf<Batch?>(null); private set
    var reels by mutableStateOf<List<GeneratedReel>>(emptyList()); private set
    var workActive by mutableStateOf(false); private set
    var loading by mutableStateOf(true); private set
    var managing by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    private var scheduling by mutableStateOf(false)
    val busy get() = loading || managing || scheduling || workActive

    init {
        viewModelScope.launch { dao.observeFolders().collect { folders = it } }
        viewModelScope.launch { dao.observeBatch().collect { batch = it } }
        viewModelScope.launch { dao.observeLatestReels().collect { reels = it } }
        viewModelScope.launch {
            val settings = dao.settings() ?: AppSettings()
            category = ReelCategory.valueOf(settings.category)
            humor = HumorStyle.valueOf(settings.humor)
            work.getWorkInfosForUniqueWorkFlow(BatchWorker.WORK_NAME).collect { infos ->
                workActive = infos.any { !it.state.isFinished }
                loading = false
            }
        }
    }
    fun choose(value: ReelCategory) { category = value; saveSettings() }
    fun chooseHumor(value: HumorStyle) { humor = value; saveSettings() }
    private fun saveSettings() { val value = AppSettings(category = category.name, humor = humor.name); viewModelScope.launch { dao.saveSettings(value) } }
    fun report(value: String) { message = value }
    private fun manage(block: suspend () -> Unit) {
        if (busy) return
        managing = true
        message = ""
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.localizedMessage ?: "Could not update folders." }
            finally { managing = false }
        }
    }
    fun addFolder(uri: Uri) {
        // Android can recreate this activity while its document picker is open.
        // Do not discard the returned selection while Room/WorkManager initializes.
        viewModelScope.launch {
            snapshotFlow { loading }.first { !it }
            addFolderWhenReady(uri)
        }
    }
    private fun addFolderWhenReady(uri: Uri) = manage {
        withContext(Dispatchers.IO) {
            val context = getApplication<Application>()
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val folder = DocumentFile.fromTreeUri(context, uri)
            require(folder != null && folder.canRead()) { "This folder is not readable. Choose a local video folder." }
            dao.addFolder(SourceFolder(uri.toString(), folder.name ?: "Source folder"))
            dao.enableFolder(uri.toString(), true)
        }
        scan()
    }
    fun enable(folder: SourceFolder, enabled: Boolean) = manage { dao.enableFolder(folder.uri, enabled) }
    fun remove(folder: SourceFolder) = manage {
        dao.removeFolder(folder.uri)
        withContext(Dispatchers.IO) {
            runCatching { getApplication<Application>().contentResolver.releasePersistableUriPermission(Uri.parse(folder.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }
    fun rescan() = manage { scan() }
    private suspend fun scan() {
        FolderScanner(getApplication(), dao).scanAll { value -> withContext(Dispatchers.Main) { message = value } }
        message = "Scan complete."
    }
    fun generate() {
        if (busy || folders.none { it.enabled }) return
        scheduling = true
        message = ""
        val request = OneTimeWorkRequestBuilder<BatchWorker>()
            .setInputData(workDataOf("category" to category.name, "humor" to humor.name)).build()
        viewModelScope.launch {
            try {
                work.enqueueUniqueWork(BatchWorker.WORK_NAME, ExistingWorkPolicy.KEEP, request).await()
                workActive = work.getWorkInfosForUniqueWorkFlow(BatchWorker.WORK_NAME).first().any { !it.state.isFinished }
            } catch (error: Exception) { message = "Could not start generation. ${error.localizedMessage}" }
            finally { scheduling = false }
        }
    }
    fun cancel() {
        viewModelScope.launch {
            val infos = work.getWorkInfosForUniqueWorkFlow(BatchWorker.WORK_NAME).first()
            infos.filter { !it.state.isFinished }.forEach { info ->
                dao.batchStatus(info.id.toString(), "CANCELLED", "Cancelling; completed reels remain saved.")
            }
            work.cancelUniqueWork(BatchWorker.WORK_NAME).await()
        }
    }
}
