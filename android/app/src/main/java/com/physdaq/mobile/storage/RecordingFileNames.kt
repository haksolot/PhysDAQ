package com.physdaq.mobile.storage

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Names for recording files.
 *
 * One take (a "session") has one stem, shared by every node's file. The stem is
 * the timestamp, then the session name the operator typed; each file adds the
 * body position the node was linked to:
 *
 *     2026-10-07_14-32-10_PhysDAQ_Session_wrist-left.csv
 *     2026-10-07_14-32-10_PhysDAQ_Session_chest.csv
 *
 * The timestamp comes first and uses the same pattern as analysis/logger.py
 * (YYYY-MM-DD_HH-MM-SS), so the files sort by time and `make process FILE=...`
 * works on any of them; the pipeline derives its output names from the file's
 * stem. Position names contain hyphens only, so the position is always what
 * follows the LAST underscore.
 */
object RecordingFileNames {

    private const val STAMP_PATTERN = "yyyy-MM-dd_HH-mm-ss"
    private val FILE = Regex("""^(\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2})_(.+)\.csv$""")

    /** Files the offline pipeline writes next to a recording; they are not recordings. */
    private val DERIVED_SUFFIXES = setOf("enriched", "beats", "spectral")

    fun stamp(date: Date): String = SimpleDateFormat(STAMP_PATTERN, Locale.ROOT).format(date)

    /** The shared part of a session's file names: the timestamp, then the session name if there is one. */
    fun stem(date: Date, sessionName: String): String {
        val name = sanitize(sessionName, empty = "")
        return if (name.isEmpty()) stamp(date) else "${stamp(date)}_$name"
    }

    fun forDevice(stem: String, deviceLabel: String): String = "${stem}_${sanitize(deviceLabel)}.csv"

    /** Keeps letters, digits, '-' and '_'; everything else (spaces, colons, '#') becomes '_'. */
    fun sanitize(label: String, empty: String = "device"): String =
        label.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .trim('_')
            .ifEmpty { empty }

    /** What a recording's file name says about it. */
    data class Parsed(val stamp: String, val sessionName: String, val position: String) {
        /** The session this file belongs to: files with the same key are one take. */
        val sessionKey: String get() = if (sessionName.isEmpty()) stamp else "${stamp}_$sessionName"
        val date: Date? get() = parseStamp(stamp)
    }

    /** Splits a recording's file name, or returns null for anything that is not one (including pipeline outputs). */
    fun parse(fileName: String): Parsed? {
        val m = FILE.matchEntire(fileName) ?: return null
        val rest = m.groupValues[2]
        val cut = rest.lastIndexOf('_')
        val name = if (cut < 0) "" else rest.substring(0, cut)
        val position = if (cut < 0) rest else rest.substring(cut + 1)
        if (position in DERIVED_SUFFIXES) return null
        return Parsed(m.groupValues[1], name, position)
    }

    fun parseStamp(stamp: String): Date? {
        val f = SimpleDateFormat(STAMP_PATTERN, Locale.ROOT).apply { isLenient = false }
        val pos = ParsePosition(0)
        val d = f.parse(stamp, pos)
        return if (d != null && pos.index == stamp.length) d else null
    }
}
