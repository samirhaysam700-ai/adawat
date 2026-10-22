package com.motalim.adawat

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.print.PrintHelper
import com.motalim.adawat.core.FileKind
import com.motalim.adawat.core.L10n
import com.motalim.adawat.core.kind
import com.motalim.adawat.ops.Images
import com.motalim.adawat.work.Outputs
import java.io.File
import java.io.FileOutputStream

/** The actions wheel, done the Android way: the share sheet, "Open with", the clipboard, a chosen folder and the print dialog. */
object Actions {
    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /** Opens the share sheet. Returns false (and tells the person) when it could not be opened, so the caller keeps its screen. */
    fun share(activity: Activity, files: List<File>): Boolean =
        sendUris(activity, files.map { uri(activity, it) }, commonType(files))

    /** Shares results already saved in the shared folders (MediaStore uris). */
    fun shareSaved(activity: Activity, uris: List<Uri>, type: String): Boolean = sendUris(activity, uris, type)

    /**
     * One path for every share: read permission is granted through both the flag and the clip data, on the send intent and on
     * the chooser, so the share sheet and the app picked in it can all read the files.
     */
    private fun sendUris(activity: Activity, uris: List<Uri>, type: String): Boolean {
        if (uris.isEmpty()) return false
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                   else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        send.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        return try {
            activity.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            true
        } catch (e: Exception) {
            android.util.Log.e("Adawat", "Share failed", e)
            Toast.makeText(activity, L10n.t("Something went wrong: {1}").replace("{1}", e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
            false
        }
    }

    fun openWith(activity: Activity, files: List<File>) {
        val file = files.first()
        open(activity, uri(activity, file), Outputs.mime(file))
    }

    fun open(activity: Activity, uri: Uri, mime: String) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(Intent.createChooser(view, null))
        } catch (_: Exception) {
            Toast.makeText(activity, L10n.t("No app can open this file."), Toast.LENGTH_SHORT).show()
        }
    }

    fun openLink(activity: Activity, link: String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
    }

    fun copy(context: Context, files: List<File>) {
        val uris = files.map { uri(context, it) }
        val clip = ClipData.newUri(context.contentResolver, files.first().name, uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        // Android 13 and later shows its own confirmation.
        if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, L10n.t("Copied"), Toast.LENGTH_SHORT).show()
    }

    /** Copies the files into a folder the person picked. */
    fun saveToTree(context: Context, tree: Uri, files: List<File>) {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var saved = 0
        for (file in files) {
            runCatching {
                val document = DocumentsContract.createDocument(resolver, parent, Outputs.mime(file), file.name) ?: return@runCatching
                resolver.openOutputStream(document)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                saved++
            }
        }
        val folder = tree.lastPathSegment?.substringAfterLast(':')?.ifEmpty { null } ?: "…"
        Toast.makeText(context, if (saved == 1) L10n.t("Saved to {1}").replace("{1}", folder)
                                else L10n.t("Saved {1} files to {2}").replace("{1}", saved.toString()).replace("{2}", folder), Toast.LENGTH_SHORT).show()
    }

    fun print(activity: Activity, files: List<File>) {
        val file = files.first()
        if (file.kind == FileKind.Image) {
            PrintHelper(activity).apply { scaleMode = PrintHelper.SCALE_MODE_FIT }.printBitmap(file.nameWithoutExtension, Images.decode(file, 4000)) {
                activity.finish()
            }
            return
        }
        val manager = activity.getSystemService(PrintManager::class.java)
        manager.print(file.nameWithoutExtension, PdfPrintAdapter(file), PrintAttributes.Builder().build())
    }

    private fun commonType(files: List<File>): String {
        val types = files.map { Outputs.mime(it) }.toSet()
        if (types.size == 1) return types.first()
        val families = types.map { it.substringBefore('/') }.toSet()
        return if (families.size == 1) "${families.first()}/*" else "*/*"
    }
}

/** Hands a PDF to the print dialog as it is. */
private class PdfPrintAdapter(private val file: File) : PrintDocumentAdapter() {
    override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
        if (cancel.isCanceled) return callback.onLayoutCancelled()
        callback.onLayoutFinished(PrintDocumentInfo.Builder(file.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
    }

    override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancel: CancellationSignal, callback: WriteResultCallback) {
        runCatching {
            file.inputStream().use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
        }.onSuccess { callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }.onFailure { callback.onWriteFailed(it.message) }
    }
}
