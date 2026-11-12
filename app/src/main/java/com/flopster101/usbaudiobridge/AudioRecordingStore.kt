package com.flopster101.usbaudiobridge

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioRecordingStore(private val context: Context, private val settings: SettingsRepository) {
    data class Output(val name: String, val descriptor: Int)

    var lastFileName: String? = null
        private set
    private var lastFileUri: Uri? = null
    private var lastFile: File? = null
    private var lastFilePending = false

    fun open(format: Int): Output {
        val extension = if (format == SettingsRepository.RECORDING_FORMAT_FLAC) "flac" else "wav"
        val name = "USB_Audio_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.$extension"
        lastFileName = name
        lastFileUri = null
        lastFile = null
        lastFilePending = false
        val directoryUri = settings.getRecordingDirectory()
        return if (directoryUri != null) {
            openInDirectory(directoryUri.toUri(), name, extension)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            openInMediaStore(name, extension)
        } else {
            openLegacy(name)
        }
    }

    fun persistDirectoryPermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        settings.saveRecordingDirectory(uri.toString())
    }

    fun useDefaultDirectory() {
        settings.saveRecordingDirectory(null)
    }

    fun directoryLabel(): String {
        val uri = settings.getRecordingDirectory() ?: return "Music/UsbAudioBridge"
        return uri.toUri().lastPathSegment ?: "Selected folder"
    }

    fun completeRecording() {
        val uri = lastFileUri
        if (lastFilePending && uri != null) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
        }
        lastFilePending = false
        lastFile?.let {
            MediaScannerConnection.scanFile(context, arrayOf(it.absolutePath), null, null)
        }
    }

    fun discardLastFile() {
        lastFileUri?.let { context.contentResolver.delete(it, null, null) }
        lastFile?.delete()
        lastFileName = null
        lastFileUri = null
        lastFile = null
        lastFilePending = false
    }

    fun createShareIntent(): Intent? {
        val name = lastFileName ?: return null
        val uri = lastFileUri ?: lastFile?.let {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
        } ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = if (name.endsWith(".flac")) "audio/flac" else "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun openInMediaStore(name: String, extension: String): Output {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(extension))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/UsbAudioBridge")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create recording in Music/UsbAudioBridge")
        lastFileUri = uri
        lastFilePending = true
        val descriptor = context.contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("Could not open recording file")
        return Output(name, descriptor.detachFd())
    }

    private fun openLegacy(name: String): Output {
        val music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val directory = File(music, "UsbAudioBridge")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Could not create Music/UsbAudioBridge")
        }
        val file = File(directory, name)
        if (file.exists()) throw IllegalStateException("Recording file already exists")
        val descriptor = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_TRUNCATE
        ).detachFd()
        lastFile = file
        return Output(name, descriptor)
    }

    private fun openInDirectory(directoryUri: Uri, name: String, extension: String): Output {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(directoryUri)
        val documentUri = DocumentsContract.createDocument(
            context.contentResolver,
            DocumentsContract.buildDocumentUriUsingTree(directoryUri, treeDocumentId),
            mimeType(extension),
            name
        ) ?: throw IllegalStateException("Could not create recording in selected folder")
        lastFileUri = documentUri
        val descriptor = context.contentResolver.openFileDescriptor(documentUri, "rw")
            ?: throw IllegalStateException("Could not open recording file")
        return Output(name, descriptor.detachFd())
    }

    private fun mimeType(extension: String) = if (extension == "flac") "audio/flac" else "audio/wav"
}
