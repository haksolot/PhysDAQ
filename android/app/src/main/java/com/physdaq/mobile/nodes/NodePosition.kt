package com.physdaq.mobile.nodes

/**
 * The places a sensor can be worn, the same eight as the desktop app's body map.
 *
 * [slug] is what goes into recording file names (`..._wrist-left.csv`) and is
 * written to match the desktop's session folders ("wrist left"). It uses hyphens
 * only, so a file name still splits cleanly on its last underscore.
 */
enum class NodePosition(val slug: String, val title: String) {
    HEAD("head", "Ear / Head"),
    CHEST("chest", "Chest (Torso)"),
    WRIST_LEFT("wrist-left", "Left Wrist"),
    WRIST_RIGHT("wrist-right", "Right Wrist"),
    FINGER_LEFT("finger-left", "Left Finger"),
    FINGER_RIGHT("finger-right", "Right Finger"),
    ANKLE_LEFT("ankle-left", "Left Ankle"),
    ANKLE_RIGHT("ankle-right", "Right Ankle");

    companion object {
        fun fromSlug(slug: String?): NodePosition? = values().firstOrNull { it.slug == slug }
    }
}
