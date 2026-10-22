package com.motalim.adawat

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motalim.adawat.core.Catalog
import com.motalim.adawat.core.L10n
import com.motalim.adawat.core.Operation
import com.motalim.adawat.core.WheelMode
import com.motalim.adawat.ui.AdawatTheme
import com.motalim.adawat.ui.WheelScreen
import com.motalim.adawat.work.Inputs
import com.motalim.adawat.work.JobState
import com.motalim.adawat.work.Jobs
import com.motalim.adawat.work.WheelVisibility
import com.motalim.adawat.work.WorkService
import com.motalim.adawat.work.stageText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Holds the shared files and the running job across rotations. */
class WheelModel(application: Application) : AndroidViewModel(application) {
    /** Null while the shared files are being copied in. */
    val files = MutableStateFlow<List<File>?>(null)
    val failed = MutableStateFlow(false)
    val mode = MutableStateFlow(WheelMode.Convert)
    val job = MutableStateFlow<String?>(null)
    /** Debug builds only: a tool whose screen opens as soon as the files are ready. */
    val testOpen = MutableStateFlow<String?>(null)
    var started = false
        private set

    fun stage(uris: List<Uri>, text: String?) {
        if (started) return
        started = true
        viewModelScope.launch {
            val staged = withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val fromUris = Inputs.stage(app, uris)
                if (fromUris.isEmpty() && !text.isNullOrBlank()) listOf(Inputs.stageText(app, text)) else fromUris
            }
            if (staged.isEmpty()) failed.value = true
            else {
                mode.value = WheelMode.entries.firstOrNull { Catalog.forMode(it, staged).isNotEmpty() } ?: WheelMode.Actions
                files.value = staged
            }
        }
    }

    fun restart(uris: List<Uri>, text: String?) {
        files.value = null
        failed.value = false
        job.value = null
        started = false
        stage(uris, text)
    }

    fun run(operation: Operation, options: Map<String, String> = emptyMap()) {
        val staged = files.value ?: return
        val id = UUID.randomUUID().toString()
        WheelVisibility.watching = id
        job.value = id
        WorkService.start(getApplication(), JobState(id, L10n.t(operation.title)), operation.id, staged, options)
    }
}

class WheelActivity : ComponentActivity() {
    private val model: WheelModel by viewModels()
    private var pendingSave: List<File> = emptyList()
    /** A folder picker or print dialog is open on top and will come back here. */
    private var awaitingResult = false
    private var printing = false
    private var paused = false

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val saveTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        awaitingResult = false
        if (tree != null) Actions.saveToTree(this, tree, pendingSave)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // One wheel at a time: a new share replaces a wheel still open from before.
        current?.get()?.takeIf { it !== this && !it.isFinishing }?.finish()
        current = java.lang.ref.WeakReference(this)
        // Brought back after its process ended (an update, low memory): the shared files' permission is gone with it.
        if (savedInstanceState != null && !model.started) {
            finish()
            return
        }
        model.stage(sharedUris(intent), intent.getStringExtra(Intent.EXTRA_TEXT))
        runTestOperation(intent)
        setContent {
            AdawatTheme {
                WheelScreen(
                    model = model,
                    onRun = { operation, options ->
                        if (operation.id.startsWith("action.")) perform(operation)
                        else {
                            askForNotifications()
                            model.run(operation, options)
                        }
                    },
                    onDismiss = ::finish,
                )
            }
        }
    }

    /** Files shared again while the wheel is open start a fresh wheel. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (model.job.value != null && Jobs.all.value[model.job.value]?.finished == false) {
            startActivity(Intent(intent).setClass(this, WheelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
            return
        }
        setIntent(intent)
        model.restart(sharedUris(intent), intent.getStringExtra(Intent.EXTRA_TEXT))
        runTestOperation(intent)
    }

    override fun onStart() {
        super.onStart()
        WheelVisibility.watching = model.job.value
    }

    override fun onPause() {
        super.onPause()
        paused = true
    }

    override fun onResume() {
        super.onResume()
        // Back from the print dialog: printing is done with the wheel.
        if (printing && paused) finish()
        paused = false
    }

    override fun onStop() {
        super.onStop()
        // The wheel is for this one share. Once it is out of sight it closes, so the next share always opens a fresh one;
        // running work carries on in the service, with its notification.
        if (!isChangingConfigurations && !awaitingResult) finish()
        // Once the wheel is gone, a finished job is announced by notification instead.
        if (WheelVisibility.watching == model.job.value) WheelVisibility.watching = null
    }

    /** Debug builds can run a tool straight away, for tests driven over adb. */
    private fun runTestOperation(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        intent.getStringExtra("adawat.test.open")?.let { model.testOpen.value = it }
        val id = intent.getStringExtra("adawat.test.operation") ?: return
        lifecycleScope.launch {
            val files = model.files.filterNotNull().first()
            // Options as "key=value;key=value".
            val options = intent.getStringExtra("adawat.test.options").orEmpty().split(';').filter { it.contains('=') }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            Catalog.find(id, files)?.let { model.run(it, options) }
        }
    }

    companion object {
        private var current: java.lang.ref.WeakReference<WheelActivity>? = null
    }

    /**
     * Shares results that are already saved. The wheel stays under the share sheet and closes when the person comes back,
     * instead of finishing the moment the sheet opens, which on some phones closes the sheet with it.
     */
    fun shareSaved(uris: List<Uri>, type: String) {
        if (Actions.shareSaved(this, uris, type)) stayUntilBack()
    }

    /** Something opened on top (the share sheet): keep the wheel alive underneath and close it when the person returns. */
    private fun stayUntilBack() {
        awaitingResult = true
        printing = true
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != 0) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun perform(operation: Operation) {
        val files = model.files.value ?: return
        when (operation.id) {
            "action.share" -> if (Actions.share(this, files)) stayUntilBack()
            "action.open" -> { Actions.openWith(this, files); finish() }
            "action.copy" -> { Actions.copy(this, files); finish() }
            "action.save" -> { pendingSave = files; awaitingResult = true; saveTree.launch(null) }
            "action.print" -> { awaitingResult = true; printing = true; Actions.print(this, files) }
        }
    }

    private fun sharedUris(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::add)
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::addAll)
            else -> intent.data?.let(uris::add)
        }
        if (uris.isEmpty()) intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
        return uris.distinct()
    }
}
