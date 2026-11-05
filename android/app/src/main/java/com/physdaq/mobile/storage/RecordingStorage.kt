package com.physdaq.mobile.storage

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.Writer

/**
 * Decides where a recording is written.
 *
 * Android 10+ (API 29): Downloads/PhysDAQ/ through MediaStore. No storage
 * permission is needed for files the app creates itself, and the file shows up
 * in the Files app and over USB (File transfer mode) under Download/PhysDAQ,
 * which makes it easy to copy next to the repo and run `make process` on.
 *
 * Older Android: the app's own external folder, which also needs no permission
 * but is harder to reach from the phone.
 */
object RecordingStorage {

    class Target(val writer: Writer, val description: String)

    fun open(context: Context, fileName: String): Target {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PhysDAQ")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create $fileName in Downloads")
            val stream = context.contentResolver.openOutputStream(uri)
                ?: error("Could not open $fileName for writing")
            return Target(stream.bufferedWriter(), "Download/PhysDAQ/$fileName")
        }

        val dir = File(context.getExternalFilesDir(null), "PhysDAQ_Sessions").apply { mkdirs() }
        val file = File(dir, fileName)
        return Target(file.bufferedWriter(), file.absolutePath)
    }
}
