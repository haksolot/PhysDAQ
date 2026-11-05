package com.physdaq.mobile

import android.content.Context
import com.physdaq.mobile.ble.BleConnectionState
import com.physdaq.mobile.ble.BleScanner
import com.physdaq.mobile.nodes.NodeAssignments
import com.physdaq.mobile.nodes.NodePosition
import com.physdaq.mobile.nodes.PrefsStore
import com.physdaq.mobile.storage.RecordingCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything that has to outlive the screen: the scanner, the connected devices,
 * the recording and the log.
 *
 * It is a process-wide singleton on purpose. The screen can be destroyed and
 * recreated (rotation, swiping away, back button) while a recording carries on,
 * so the state cannot live in the Activity. [AcquisitionService] keeps the
 * process alive and shows the notification; this object holds the actual work.
 * Both the Activity and the Service call [init], which is safe to repeat.
 *
 * Main thread only.
 */
object AcquisitionRuntime {

    private var initialized = false
    private lateinit var appContext: Context

    lateinit var scanner: BleScanner
        private set
    lateinit var recording: RecordingCoordinator
        private set

    /** Which body position each device was linked to; remembered across restarts. */
    lateinit var nodes: NodeAssignments
        private set

    private lateinit var prefs: PrefsStore

    /** What the operator called the next recording. Part of every file name; remembered across restarts. */
    var sessionName: String
        get() = prefs.get(KEY_SESSION_NAME) ?: DEFAULT_SESSION_NAME
        set(value) = prefs.put(KEY_SESSION_NAME, value)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _sessions = MutableStateFlow<List<DeviceSession>>(emptyList())
    val sessions: StateFlow<List<DeviceSession>> = _sessions

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        prefs = PrefsStore(appContext)
        nodes = NodeAssignments(prefs)
        scanner = BleScanner(appContext)
        recording = RecordingCoordinator(appContext, scope)
        scope.launch { scanner.notice.filterNotNull().collect { addLog("!! $it") } }
    }

    /** Connects to a device, creating its session the first time. Stops any scan first: scanning while connecting makes connections flaky. */
    fun connect(address: String, scannedName: String?) {
        scanner.stop()
        val existing = _sessions.value.firstOrNull { it.address == address }
        if (existing != null) {
            existing.connect()
            return
        }
        val session = DeviceSession(appContext, address, scannedName, scope, ::addLog)
        _sessions.update { it + session }
        session.connect()
    }

    /**
     * Links a device to a body position and connects it. A position holds one
     * device at a time: if another device is still linked there, nothing happens
     * and the log says so (use that device's Kill first).
     */
    fun link(address: String, scannedName: String?, position: NodePosition) {
        val taken = _sessions.value.any { it.address != address && nodes.positionOf(it.address) == position }
        if (taken) {
            addLog("!! ${position.title} already has a device linked")
            return
        }
        nodes.assign(address, position)
        connect(address, scannedName)
    }

    /** Disconnects one device, closes its recording file and removes it. Other devices carry on. */
    fun disconnect(address: String) {
        val session = _sessions.value.firstOrNull { it.address == address } ?: return
        recording.stop(address)
        session.close()
        _sessions.update { list -> list.filter { it.address != address } }
    }

    /** Ends the recording and every connection. Used by Disconnect all and the notification's Stop. */
    fun stopAll() {
        recording.stopAll()
        _sessions.value.forEach { it.close() }
        _sessions.value = emptyList()
    }

    fun addLog(text: String) {
        _log.update { (listOf(text) + it).take(LOG_MAX) }
    }

    /** One line for the notification: how many devices are connected, and whether it is recording. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun summary(): Flow<String> {
        val connection: Flow<String> = _sessions.flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf("No devices")
            } else {
                combine(list.map { it.client.connectionState }) { states ->
                    val connected = states.count { it is BleConnectionState.Connected }
                    "$connected of ${states.size} connected"
                }
            }
        }
        return combine(connection, recording.active) { text, rec ->
            text + if (rec) " - recording" else ""
        }
    }

    private const val LOG_MAX = 300
    private const val KEY_SESSION_NAME = "session_name"
    const val DEFAULT_SESSION_NAME = "PhysDAQ_Session"
}
