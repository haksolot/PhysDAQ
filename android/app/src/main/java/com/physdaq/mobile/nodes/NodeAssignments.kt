package com.physdaq.mobile.nodes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A tiny persistent string store. The app backs it with SharedPreferences; tests use a map. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/**
 * Which body position each device (by Bluetooth address) was linked to.
 *
 * It is remembered across app restarts, so the next time a device appears in
 * the scan list the app can say "last worn at: Left Wrist". Disconnecting a
 * device does NOT forget its position; only linking it somewhere else changes it.
 *
 * Main thread only.
 */
class NodeAssignments(private val store: KeyValueStore) {

    private val _map = MutableStateFlow(load())
    val map: StateFlow<Map<String, NodePosition>> = _map

    fun positionOf(address: String): NodePosition? = _map.value[address]

    fun assign(address: String, position: NodePosition) {
        _map.value = _map.value + (address to position)
        save()
    }

    fun forget(address: String) {
        if (address !in _map.value) return
        _map.value = _map.value - address
        save()
    }

    private fun load(): Map<String, NodePosition> {
        val raw = store.get(KEY) ?: return emptyMap()
        val out = LinkedHashMap<String, NodePosition>()
        for (entry in raw.split(';')) {
            val i = entry.indexOf('=')
            if (i <= 0) continue
            val position = NodePosition.fromSlug(entry.substring(i + 1)) ?: continue
            out[entry.substring(0, i)] = position
        }
        return out
    }

    private fun save() {
        store.put(KEY, _map.value.entries.joinToString(";") { "${it.key}=${it.value.slug}" })
    }

    private companion object {
        const val KEY = "node_positions"
    }
}
