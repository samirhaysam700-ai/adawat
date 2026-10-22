package com.motalim.adawat.ui

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motalim.adawat.Actions
import com.motalim.adawat.WheelActivity
import com.motalim.adawat.WheelModel
import com.motalim.adawat.core.Catalog
import com.motalim.adawat.core.FileKind
import com.motalim.adawat.core.L10n
import com.motalim.adawat.core.Operation
import com.motalim.adawat.core.WheelMode
import com.motalim.adawat.core.kind
import com.motalim.adawat.wheel.Wheel
import com.motalim.adawat.ui.editors.EditorHost
import com.motalim.adawat.ui.editors.editorScreens
import com.motalim.adawat.work.JobState
import com.motalim.adawat.work.Jobs
import com.motalim.adawat.work.Outputs
import com.motalim.adawat.work.WorkService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun modeTitle(mode: WheelMode) = when (mode) {
    WheelMode.Convert -> "Convert"
    WheelMode.Tools -> "Tools"
    WheelMode.Actions -> "Actions"
}

@Composable
fun WheelScreen(model: WheelModel, onRun: (Operation, Map<String, String>) -> Unit, onDismiss: () -> Unit) {
    val files by model.files.collectAsState()
    val failed by model.failed.collectAsState()
    val mode by model.mode.collectAsState()
    val jobId by model.job.collectAsState()
    val jobs by Jobs.all.collectAsState()
    var details by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<Operation?>(null) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scrim by animateFloatAsState(if (shown) 0.42f else 0f, tween(180), label = "scrim")
    val job = jobId?.let { jobs[it] }
    // Back from a tool's screen returns to the wheel, rather than closing everything.
    androidx.activity.compose.BackHandler(enabled = (choosing != null || details) && jobId == null) { choosing = null; details = false }
    val testOpen by model.testOpen.collectAsState()
    LaunchedEffect(files, testOpen) {
        val staged = files ?: return@LaunchedEffect
        val id = testOpen ?: return@LaunchedEffect
        choosing = Catalog.find(id, staged)
        model.testOpen.value = null
    }
    val busy = job != null && !job.finished

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = scrim))
            .clickable(remember { MutableInteractionSource() }, indication = null) { if (!busy) onDismiss() }
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        val state = when {
            failed -> "failed"
            files == null -> "loading"
            jobId != null -> "job"
            details -> "details"
            choosing?.window in editorScreens -> "editor"
            choosing != null -> "options"
            else -> "wheel"
        }
        AnimatedContent(state, transitionSpec = { ((fadeIn(tween(160)) + scaleIn(initialScale = 0.96f)) togetherWith fadeOut(tween(100))).using(SizeTransform(clip = false)) }, label = "screen") { current ->
            when (current) {
                "failed" -> Card { Message(L10n.t("These files could not be opened."), onDismiss) }
                "loading" -> Pill {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Look.Accent)
                    Spacer(Modifier.width(10.dp))
                    Text(L10n.t("Preparing files…"), color = Look.Ink, style = MaterialTheme.typography.bodyMedium)
                }
                "job" -> job?.let { JobCard(it, onDismiss) }
                "details" -> files?.let { DetailsCard(it) { details = false } }
                "editor" -> files?.let { staged -> choosing?.let { op -> EditorHost(op, staged, onRun = { onRun(op, it) }, onBack = { choosing = null }) } }
                "options" -> files?.let { staged -> choosing?.let { op -> OptionsCard(op, staged, onRun = { onRun(op, it) }, onBack = { choosing = null }) } }
                else -> files?.let { staged ->
                    val operations = remember(mode, staged) { Catalog.forMode(mode, staged) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FileChip(staged)
                        Spacer(Modifier.height(18.dp))
                        Wheel(
                            operations = operations,
                            centerText = if (operations.isEmpty()) L10n.upper(L10n.t("NO OPTIONS")) else L10n.upper(L10n.t(modeTitle(mode))),
                            onChoose = {
                                when {
                                    it.id == "action.details" -> details = true
                                    it.window in optionScreens || it.window in editorScreens -> choosing = it
                                    else -> onRun(it, emptyMap())
                                }
                            },
                            onCenter = { model.mode.value = WheelMode.entries[(mode.ordinal + 1) % 3] },
                        )
                        Spacer(Modifier.height(18.dp))
                        ModeTabs(mode) { model.mode.value = it }
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(content: @Composable () -> Unit) {
    Row(
        Modifier
            .shadow(8.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(Color(0xF7FFFFFF))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.widthIn(max = 340.dp).padding(horizontal = 24.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        shape = RoundedCornerShape(24.dp), color = Color(0xFCFFFFFF), shadowElevation = 12.dp,
    ) { Column(Modifier.padding(22.dp)) { content() } }
}

@Composable
private fun Message(text: String, onClose: () -> Unit) {
    Text(text, color = Look.Ink, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(16.dp))
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(L10n.t("Close")) }
}

private fun kindIcon(files: List<File>): Char = when (files.map { it.kind }.toSet().singleOrNull()) {
    FileKind.Image -> Catalog.Icons.Photo
    FileKind.Video -> Catalog.Icons.Film
    FileKind.Audio -> Catalog.Icons.Audio
    FileKind.Pdf -> Catalog.Icons.Pdf
    FileKind.Archive -> Catalog.Icons.Archive
    else -> Catalog.Icons.Document
}

@Composable
private fun FileChip(files: List<File>) {
    val context = LocalContext.current
    val total = remember(files) { files.sumOf { it.length() } }
    Pill {
        Text(iconText(kindIcon(files)), fontFamily = Look.Icons, fontSize = 18.sp, color = Look.Accent)
        Spacer(Modifier.width(8.dp))
        Text(
            if (files.size == 1) files[0].name else L10n.t("{1} files").replace("{1}", files.size.toString()),
            color = Look.Ink, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.widthIn(max = 220.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text("\u2066" + Formatter.formatShortFileSize(context, total) + "\u2069", color = Look.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ModeTabs(mode: WheelMode, onSelect: (WheelMode) -> Unit) {
    Row(
        Modifier
            .shadow(8.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(Color(0xF7FFFFFF))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        WheelMode.entries.forEach { entry ->
            val selected = entry == mode
            Text(
                L10n.t(modeTitle(entry)),
                color = if (selected) Color.White else Look.Ink,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Look.Accent else Color.Transparent)
                    .clickable { onSelect(entry) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun JobCard(job: JobState, onDismiss: () -> Unit) {
    val activity = LocalContext.current as Activity
    Card {
        Text(job.title, style = MaterialTheme.typography.titleMedium, color = Look.Ink)
        Spacer(Modifier.height(10.dp))
        when {
            !job.finished -> {
                Text(job.status.ifEmpty { L10n.t("Working…") }, style = MaterialTheme.typography.bodyMedium, color = Look.Muted)
                Spacer(Modifier.height(12.dp))
                val progress = job.progress
                if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Look.Accent)
                else LinearProgressIndicator({ progress.toFloat() }, Modifier.fillMaxWidth(), color = Look.Accent)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { WorkService.cancel(activity, job.id) }) { Text(L10n.t("Cancel"), color = Look.Failure) }
                    TextButton(onClick = onDismiss) { Text(L10n.t("Keep working in the background")) }
                }
            }
            job.error != null -> {
                Text(job.error, style = MaterialTheme.typography.bodyMedium, color = Look.Failure)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(L10n.t("Close")) }
            }
            else -> {
                val saved = job.saved
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(iconText(''), fontFamily = Look.Icons, fontSize = 20.sp, color = Color(0xFF1FA67A))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        job.message ?: saved.firstOrNull()?.let { L10n.t("Saved to {1}").replace("{1}", it.folder) } ?: L10n.t("Done"),
                        style = MaterialTheme.typography.bodyMedium, color = Look.Ink,
                    )
                }
                if (saved.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    saved.take(4).forEach {
                        Text(it.name, style = MaterialTheme.typography.bodySmall, color = Look.Muted, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                    if (saved.size > 4) Text("+${saved.size - 4}", style = MaterialTheme.typography.bodySmall, color = Look.Muted)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (saved.isNotEmpty()) {
                        Button(onClick = { Actions.open(activity, saved[0].uri, saved[0].mime); activity.finish() }, modifier = Modifier.weight(1f),
                               contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Text(L10n.t("Open"), maxLines = 1)
                        }
                        Button(
                            onClick = {
                                val types = saved.map { it.mime }.toSet()
                                val uris = saved.map { it.uri }
                                val type = types.singleOrNull() ?: "*/*"
                                if (activity is WheelActivity) activity.shareSaved(uris, type)
                                else if (Actions.shareSaved(activity, uris, type)) activity.finish()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Look.AccentSoft, contentColor = Look.Accent),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                        ) { Text(L10n.t("Share"), maxLines = 1) }
                    }
                    job.link?.let { link ->
                        Button(onClick = { Actions.openLink(activity, link); activity.finish() }, modifier = Modifier.weight(1f)) {
                            Text(L10n.t("Open Link"), maxLines = 1)
                        }
                    }
                    TextButton(onClick = onDismiss) { Text(L10n.t("Done")) }
                }
            }
        }
    }
}

@Composable
private fun DetailsCard(files: List<File>, onClose: () -> Unit) {
    val context = LocalContext.current
    val rows by produceState(emptyList<Pair<String, String>>(), files) {
        value = withContext(Dispatchers.IO) {
            files.take(6).flatMapIndexed { index, file ->
                buildList {
                    if (index > 0) add("" to "")
                    add(L10n.t("Name") to file.name)
                    add(L10n.t("Size") to Formatter.formatFileSize(context, file.length()))
                    add(L10n.t("Type") to Outputs.mime(file))
                    when (file.kind) {
                        FileKind.Image -> runCatching {
                            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(file.path, options)
                            if (options.outWidth > 0) add(L10n.t("Dimensions") to "${options.outWidth} × ${options.outHeight}")
                        }
                        FileKind.Video, FileKind.Audio -> runCatching {
                            MediaMetadataRetriever().use { retriever ->
                                retriever.setDataSource(file.path)
                                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let {
                                    add(L10n.t("Duration") to DateUtils.formatElapsedTime(it / 1000))
                                }
                                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                                if (w != null && h != null) add(L10n.t("Dimensions") to "$w × $h")
                            }
                        }
                        FileKind.Pdf -> runCatching {
                            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                                PdfRenderer(pfd).use { add(L10n.t("Pages") to it.pageCount.toString()) }
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
    Card {
        Text(L10n.t("Details"), style = MaterialTheme.typography.titleMedium, color = Look.Ink)
        Spacer(Modifier.height(12.dp))
        rows.forEach { (label, value) ->
            if (label.isEmpty()) { Spacer(Modifier.height(10.dp)); return@forEach }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = Look.Muted, modifier = Modifier.width(92.dp))
                // Isolated, so "73 kB" and "1024 × 768" keep their order inside Arabic.
                Text("\u2068$value\u2069", style = MaterialTheme.typography.bodySmall, color = Look.Ink, textAlign = TextAlign.Start)
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(L10n.t("Close")) }
    }
}
