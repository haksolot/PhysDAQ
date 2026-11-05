package com.physdaq.mobile.nodes

/**
 * Small judgement calls about nodes, kept free of Android so they can be unit tested.
 */
object NodeChecks {

    const val SLEEP_HINT = "Device may have gone to sleep: move it or touch the sensor to wake it"

    /**
     * True if a link-loss notice looks like the node went quiet rather than refusing us.
     *
     * Android reports GATT status 8 (connection supervision timeout) when the other
     * side simply stops answering. For a PhysDAQ node that almost always means its
     * deep sleep kicked in (no motion and no skin contact for about 10 s on fw 1.1.0),
     * not a fault. The client's notice reads "Link lost (status 8)".
     */
    fun looksAsleep(notice: String): Boolean {
        val n = notice.lowercase()
        return Regex("""status\s*[=:]?\s*(8|0x0?8)\b""").containsMatchIn(n) ||
            n.contains("supervision timeout")
    }

    /** What the Record button needs to know about one linked body position. */
    data class Slot(val title: String, val connected: Boolean, val worn: Boolean)

    /**
     * @property issues one line per problem, e.g. "Left Wrist is not worn."
     * @property recordable how many nodes would actually be recorded
     */
    data class Preflight(val issues: List<String>, val recordable: Int) {
        val ok: Boolean get() = issues.isEmpty()
    }

    /** Checks the linked positions before a recording starts. */
    fun preflight(slots: List<Slot>): Preflight {
        val issues = ArrayList<String>()
        for (s in slots) {
            when {
                !s.connected -> issues.add("${s.title} is disconnected and will not be recorded.")
                !s.worn -> issues.add("${s.title} is not worn.")
            }
        }
        return Preflight(issues, slots.count { it.connected })
    }
}
