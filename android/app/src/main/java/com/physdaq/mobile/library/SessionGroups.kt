package com.physdaq.mobile.library

import com.physdaq.mobile.nodes.NodePosition
import com.physdaq.mobile.storage.RecordingFileNames
import java.util.Date

/** One CSV on the phone. [uri] is a content:// or file:// string; the repository knows how to open it. */
data class LibraryFile(val name: String, val uri: String, val sizeBytes: Long)

/** One take: the files that share a timestamp and session name, one per node. */
data class SessionGroup(
    /** Same as RecordingFileNames.Parsed.sessionKey, or the file name for a file that is not a recording. */
    val key: String,
    val title: String,
    val date: Date?,
    val members: List<Member>,
    /** False for files whose names do not follow the recording pattern (a desktop file, a pipeline output). */
    val isRecording: Boolean
) {
    class Member(val label: String, val position: NodePosition?, val file: LibraryFile)

    val sizeBytes: Long get() = members.sumOf { it.file.sizeBytes }
}

object SessionGroups {

    /** Newest session first; files that are not recordings go last, one group each. */
    fun group(files: List<LibraryFile>): List<SessionGroup> {
        val byKey = LinkedHashMap<String, MutableList<Pair<RecordingFileNames.Parsed, LibraryFile>>>()
        val others = ArrayList<LibraryFile>()
        for (f in files) {
            val p = RecordingFileNames.parse(f.name)
            if (p == null) others.add(f) else byKey.getOrPut(p.sessionKey) { ArrayList() }.add(p to f)
        }

        val sessions = byKey.map { (key, list) ->
            val first = list[0].first
            SessionGroup(
                key = key,
                title = titleOf(first.sessionName),
                date = first.date,
                members = list
                    .map { (p, f) ->
                        val pos = NodePosition.fromSlug(p.position)
                        SessionGroup.Member(pos?.title ?: p.position, pos, f)
                    }
                    .sortedWith(compareBy({ it.position?.ordinal ?: Int.MAX_VALUE }, { it.label })),
                isRecording = true
            )
        }.sortedByDescending { it.key }

        val rest = others.sortedBy { it.name.lowercase() }.map {
            SessionGroup(
                key = it.name,
                title = it.name,
                date = null,
                members = listOf(SessionGroup.Member(it.name, null, it)),
                isRecording = false
            )
        }
        return sessions + rest
    }

    /** "PhysDAQ_Session" -> "PhysDAQ Session"; no name -> "Untitled session". */
    fun titleOf(sessionName: String): String =
        if (sessionName.isEmpty()) "Untitled session" else sessionName.replace('_', ' ')
}
