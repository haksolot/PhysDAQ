package com.physdaq.mobile.library

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream

/**
 * Finds, opens and deletes the CSV files on the phone. Counterpart of
 * RecordingStorage, which writes them: Downloads/PhysDAQ through MediaStore on
 * Android 10+, the app's own external folder before that.
 *
 * Android only lets an app list files in Downloads that IT created, so after an
 * uninstall/reinstall older recordings no longer show up here; the Library's
 * "Open CSV file" button reads them through the system file picker instead.
 *
 * All calls block on disk: call them off the main thread.
 */
object LibraryRepository {

    fun list(context: Context): List<LibraryFile> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listMediaStore(context) else listFolder(context)

    private fun listMediaStore(context: Context): List<LibraryFile> {
        val base = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/PhysDAQ%")
        val out = ArrayList<LibraryFile>()
        context.contentResolver.query(base, projection, selection, args, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (c.moveToNext()) {
                val name = c.getString(nameCol) ?: continue
                if (!name.endsWith(".csv", ignoreCase = true)) continue
                val uri = ContentUris.withAppendedId(base, c.getLong(idCol))
                out.add(LibraryFile(name, uri.toString(), c.getLong(sizeCol)))
            }
        }
        return out
    }

    private fun listFolder(context: Context): List<LibraryFile> {
        val dir = File(context.getExternalFilesDir(null), "PhysDAQ_Sessions")
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".csv", ignoreCase = true) }
            ?.map { LibraryFile(it.name, Uri.fromFile(it).toString(), it.length()) }
            ?: emptyList()
    }

    fun open(context: Context, file: LibraryFile): InputStream =
        context.contentResolver.openInputStream(Uri.parse(file.uri))
            ?: error("Could not open ${file.name}")

    /** True if the file is gone afterwards. */
    fun delete(context: Context, file: LibraryFile): Boolean = try {
        val uri = Uri.parse(file.uri)
        if (uri.scheme == "file") {
            File(requireNotNull(uri.path)).delete()
        } else {
            context.contentResolver.delete(uri, null, null) > 0
        }
    } catch (e: SecurityException) {
        false
    }

    /** A file chosen with the system picker (kept only while the Library shows it). */
    fun describePicked(context: Context, uri: Uri): LibraryFile {
        var name = uri.lastPathSegment ?: "file.csv"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0) c.getString(n)?.let { name = it }
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
            }
        }
        return LibraryFile(name, uri.toString(), size)
    }
}
