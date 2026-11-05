package com.physdaq.mobile

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.physdaq.mobile.ble.BleConnectionState
import com.physdaq.mobile.ble.BlePermissions
import com.physdaq.mobile.ble.DeviceKind
import com.physdaq.mobile.ble.ScannedDevice
import com.physdaq.mobile.ble.StreamStats
import com.physdaq.mobile.ble.label
import com.physdaq.mobile.storage.FileStatus
import com.physdaq.mobile.storage.RecordTarget
import com.physdaq.mobile.ui.BodyDot
import com.physdaq.mobile.ui.BodyMapView
import com.physdaq.mobile.ui.LiveChart
import com.physdaq.mobile.ui.TiltIndicator
import com.physdaq.mobile.ui.theme.Mono
import com.physdaq.mobile.ui.theme.PhysDaqColors
import com.physdaq.mobile.ui.theme.PhysDaqTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import java.util.Locale
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import com.physdaq.mobile.library.LibraryScreen
import com.physdaq.mobile.nodes.NodeChecks
import com.physdaq.mobile.nodes.NodePosition

/** Elapsed-time zero for the recording timer. Process-wide so a rotation does not reset it. */
private var recordStartMs = 0L

/**
 * The app's screen. All real state lives in [AcquisitionRuntime], so this
 * Activity can be destroyed and recreated (rotation, swipe-away) without
 * touching the connections or an open recording.
 *
 * Layout follows the desktop app: a session-name + record card, then a
 * "Sensor nodes" inventory with one row per body position (Link / Detail / Kill).
 */
class MainActivity : ComponentActivity() {

    private var afterPermissions: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val action = afterPermissions
            afterPermissions = null
            if (BlePermissions.hasAll(this)) {
                action?.invoke()
            } else {
                AcquisitionRuntime.addLog(
                    "!! Bluetooth permission denied. Enable Nearby devices in the app's system settings."
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AcquisitionRuntime.init(applicationContext)
        setContent {
            PhysDaqTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) { Root() }
            }
        }
    }

    override fun onStop() {
        // A scan is only useful while you are looking at the list.
        AcquisitionRuntime.scanner.stop()
        super.onStop()
    }

    private fun ensurePermissions(action: () -> Unit) {
        if (BlePermissions.hasAll(this)) {
            action()
        } else {
            afterPermissions = action
            permissionLauncher.launch(BlePermissions.requestList())
        }
    }

    /** Start the service first so Android treats the process as doing foreground work, then link. */
    private fun linkTo(device: ScannedDevice, position: NodePosition) = ensurePermissions {
        ContextCompat.startForegroundService(this, Intent(this, AcquisitionService::class.java))
        AcquisitionRuntime.link(device.address, device.name, position)
    }

    private fun disconnectDevice(address: String) {
        AcquisitionRuntime.disconnect(address)
        if (AcquisitionRuntime.sessions.value.isEmpty()) {
            stopService(Intent(this, AcquisitionService::class.java))
        }
    }

    private fun disconnectAll() {
        AcquisitionRuntime.stopAll()
        stopService(Intent(this, AcquisitionService::class.java))
    }

    /** What is wrong with the linked nodes right now (empty list = all connected and worn). */
    private fun preflight(): NodeChecks.Preflight {
        val rt = AcquisitionRuntime
        val slots = rt.sessions.value.map { s ->
            NodeChecks.Slot(
                title = rt.nodes.positionOf(s.address)?.title ?: s.label,
                connected = s.client.connectionState.value is BleConnectionState.Connected,
                worn = s.heartRate.value.contact
            )
        }
        return NodeChecks.preflight(slots)
    }

    private fun startRecording() {
        val rt = AcquisitionRuntime
        val targets = rt.sessions.value
            .filter { it.client.connectionState.value is BleConnectionState.Connected }
            .map {
                // The body position becomes the file-name suffix (e.g. ..._wrist-left.csv).
                val label = rt.nodes.positionOf(it.address)?.slug ?: it.label
                RecordTarget(it.address, label, it.client.lines)
            }
        if (targets.isEmpty()) {
            rt.addLog("!! No connected device to record")
        } else {
            recordStartMs = SystemClock.elapsedRealtime()
            rt.recording.start(targets, rt.sessionName)
        }
    }

    /** Logo, Live / Library switch, and the selected page. */
    @Composable
    private fun Root() {
        var tab by rememberSaveable { mutableStateOf(0) }
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Box(Modifier.padding(horizontal = 16.dp)) { Header() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TabButton("Live", tab == 0, Modifier.weight(1f)) { tab = 0 }
                TabButton("Library", tab == 1, Modifier.weight(1f)) { tab = 1 }
            }
            Box(Modifier.weight(1f)) {
                if (tab == 0) Screen() else LibraryScreen()
            }
        }
    }

    @Composable
    private fun TabButton(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
        if (selected) {
            Button(modifier = modifier, shape = RoundedCornerShape(10.dp), onClick = onClick) { Text(text) }
        } else {
            OutlinedButton(modifier = modifier, shape = RoundedCornerShape(10.dp), onClick = onClick) { Text(text) }
        }
    }

    @Composable
    private fun Screen() {
        val rt = AcquisitionRuntime
        val sessions by rt.sessions.collectAsStateWithLifecycle()
        val assignments by rt.nodes.map.collectAsStateWithLifecycle()
        val files by rt.recording.status.collectAsStateWithLifecycle()
        val recordingActive by rt.recording.active.collectAsStateWithLifecycle()
        val log by rt.log.collectAsStateWithLifecycle()

        var linkTarget by remember { mutableStateOf<NodePosition?>(null) }
        var detailSlug by rememberSaveable { mutableStateOf<String?>(null) }
        var sessionName by rememberSaveable { mutableStateOf(rt.sessionName) }
        var warning by remember { mutableStateOf<NodeChecks.Preflight?>(null) }

        warning?.let { p ->
            AlertDialog(
                onDismissRequest = { warning = null },
                title = { Text(if (p.recordable == 0) "Nothing to record" else "Record anyway?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        p.issues.forEach { Text(it) }
                        if (p.recordable == 0) {
                            Text(
                                "Connect at least one node first.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    if (p.recordable > 0) {
                        TextButton(onClick = { warning = null; startRecording() }) { Text("Record anyway") }
                    } else {
                        TextButton(onClick = { warning = null }) { Text("OK") }
                    }
                },
                dismissButton = {
                    if (p.recordable > 0) TextButton(onClick = { warning = null }) { Text("Cancel") }
                }
            )
        }

        val target = linkTarget
        if (target != null) {
            LinkDialog(target, onPick = { d -> linkTo(d, target); linkTarget = null }, onDismiss = { linkTarget = null })
        }

        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        // Status colour per occupied position, for the body map (same colours as the rows).
        val dotFills = HashMap<NodePosition, Color>()
        sessions.forEach { s ->
            key(s.address) {
                val conn by s.client.connectionState.collectAsStateWithLifecycle()
                val hr by s.heartRate.collectAsStateWithLifecycle()
                assignments[s.address]?.let { dotFills[it] = statusColor(conn, hr.contact) }
            }
        }
        val dots = NodePosition.values().associateWith { pos ->
            BodyDot(fill = dotFills[pos], selected = detailSlug == pos.slug)
        }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            state = listState,
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                PanelCard {
                    SectionLabel("Session recording")
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = sessionName,
                        onValueChange = { sessionName = it; rt.sessionName = it },
                        enabled = !recordingActive,
                        singleLine = true,
                        label = { Text("Session name") },
                        shape = RoundedCornerShape(10.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                recordTimerText(recordingActive),
                                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = Mono),
                                color = if (recordingActive) PhysDaqColors.Red else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (recordingActive) "Recording" else "Ready",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            enabled = sessions.isNotEmpty() || recordingActive,
                            colors = if (recordingActive) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                )
                            } else {
                                ButtonDefaults.buttonColors()
                            },
                            shape = RoundedCornerShape(10.dp),
                            onClick = {
                                if (recordingActive) {
                                    rt.recording.stopAll()
                                } else {
                                    val p = preflight()
                                    if (p.ok) { startRecording() } else { warning = p }
                                }
                            }
                        ) { Text(if (recordingActive) "Stop" else "Record") }
                    }
                    files.forEach {
                        Text(
                            describeFile(it),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                PanelCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionLabel("Sensor nodes (${sessions.size}/${NodePosition.values().size})")
                        OutlinedButton(
                            enabled = sessions.isNotEmpty(),
                            shape = RoundedCornerShape(10.dp),
                            onClick = { disconnectAll() }
                        ) { Text("Disconnect all") }
                    }
                    BodyMapView(
                        dots = dots,
                        bodyColor = PhysDaqColors.Overlay.copy(alpha = 0.45f),
                        emptyColor = PhysDaqColors.Overlay,
                        selectedColor = PhysDaqColors.Pink,
                        onTap = { pos ->
                            if (dotFills.containsKey(pos)) {
                                detailSlug = pos.slug
                                // Rows start after the recording card and this card.
                                scope.launch { listState.animateScrollToItem(2 + pos.ordinal) }
                            } else {
                                linkTarget = pos
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Text(
                        "Front view: the person's left is on your right. Tap a dot to link or open it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MapLegend()
                    if (sessions.isEmpty()) {
                        Text(
                            "Tap Link on a body position, then pick the device worn there.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(NodePosition.values().toList(), key = { "slot-" + it.slug }) { position ->
                val session = sessions.firstOrNull { assignments[it.address] == position }
                NodeSlot(
                    position = position,
                    session = session,
                    expanded = detailSlug == position.slug,
                    onLink = { linkTarget = position },
                    onDetail = { detailSlug = if (detailSlug == position.slug) null else position.slug },
                    onKill = { s -> disconnectDevice(s.address) }
                )
            }

            item {
                var logOpen by rememberSaveable { mutableStateOf(true) }
                val problems = log.count { it.startsWith("!!") }
                PanelCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { logOpen = !logOpen },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SectionLabel("System log (${log.size})")
                            if (problems > 0) {
                                Text(
                                    "$problems problem" + if (problems == 1) "" else "s",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PhysDaqColors.Red
                                )
                            }
                        }
                        Text(
                            if (logOpen) "Hide  \u25B2" else "Show  \u25BC",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (logOpen) {
                        // Fixed height with its own scrolling; newest line first (see AcquisitionRuntime.addLog).
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.background)
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (log.isEmpty()) {
                                Text("Nothing yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            log.forEach {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                                    color = if (it.startsWith("!!")) PhysDaqColors.Red else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** Scan list for one body position. Scans while open, stops when closed. */
    @Composable
    private fun LinkDialog(position: NodePosition, onPick: (ScannedDevice) -> Unit, onDismiss: () -> Unit) {
        val rt = AcquisitionRuntime
        val scanning by rt.scanner.scanning.collectAsStateWithLifecycle()
        val scanned by rt.scanner.devices.collectAsStateWithLifecycle()
        val sessions by rt.sessions.collectAsStateWithLifecycle()
        val assignments by rt.nodes.map.collectAsStateWithLifecycle()
        val candidates = scanned.filter { d -> sessions.none { it.address == d.address } }

        LaunchedEffect(Unit) { ensurePermissions { rt.scanner.start() } }

        AlertDialog(
            onDismissRequest = { rt.scanner.stop(); onDismiss() },
            title = { Text("Link ${position.title}") },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (candidates.isEmpty()) {
                        Text(
                            if (scanning) "Scanning... wake the device if it is asleep." else "No devices found.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (scanning) PhysDaqColors.Mauve else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    candidates.forEach { d ->
                        val last = assignments[d.address]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { rt.scanner.stop(); onPick(d) }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(d.name ?: "(no name)", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${kindLabel(d)}  ${d.rssi} dBm",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (last != null) {
                                    Text(
                                        "last worn at: ${last.title}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text("Link", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { if (scanning) rt.scanner.stop() else ensurePermissions { rt.scanner.start() } }) {
                    Text(if (scanning) "Stop scan" else "Rescan")
                }
            },
            dismissButton = { TextButton(onClick = { rt.scanner.stop(); onDismiss() }) { Text("Cancel") } }
        )
    }

    /** One row of the inventory; expands to charts and stream stats. */
    @Composable
    private fun NodeSlot(
        position: NodePosition,
        session: DeviceSession?,
        expanded: Boolean,
        onLink: () -> Unit,
        onDetail: () -> Unit,
        onKill: (DeviceSession) -> Unit
    ) {
        if (session == null) {
            PanelCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(PhysDaqColors.Overlay))
                        Column {
                            Text(position.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "No device linked",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    OutlinedButton(shape = RoundedCornerShape(10.dp), onClick = onLink) { Text("Link") }
                }
            }
            return
        }

        val conn by session.client.connectionState.collectAsStateWithLifecycle()
        val hr by session.heartRate.collectAsStateWithLifecycle()
        val identity by session.identity.collectAsStateWithLifecycle()
        val battery by session.battery.collectAsStateWithLifecycle()
        val maybeAsleep by session.maybeAsleep.collectAsStateWithLifecycle()
        val bat = battery
        val id = identity
        val bpm = hr.bpm
        val dot = statusColor(conn, hr.contact)

        val statusText = when {
            conn is BleConnectionState.Connected && hr.contact -> "LIVE"
            conn is BleConnectionState.Connected -> "NOT WORN"
            else -> conn.label().uppercase(Locale.ROOT)
        } + if (bat == null) "" else "  |  ${bat.percent}%"

        PanelCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
                    Column {
                        Text(position.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                            color = dot
                        )
                    }
                }
                Text(
                    if (hr.contact && bpm != null) "${bpm.toInt()} BPM" else "-- BPM",
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = Mono),
                    color = if (hr.contact && bpm != null) PhysDaqColors.Pink else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (maybeAsleep && conn !is BleConnectionState.Connected) {
                Text(
                    NodeChecks.SLEEP_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = PhysDaqColors.Amber
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    onClick = onDetail
                ) { Text(if (expanded) "Hide" else "Detail") }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    onClick = { onKill(session) }
                ) { Text("Kill") }
            }
            if (expanded) {
                var snap by remember(session) { mutableStateOf(session.stats.snapshot()) }
                LaunchedEffect(session) {
                    while (true) {
                        delay(1000)
                        snap = session.stats.snapshot()
                    }
                }
                HeartRateRow(hr)
                Text(
                    (if (id != null) "${id.name}  -  ${id.model}, ${id.ppgCount} PPG\n" else "") +
                        (if (bat == null) "Battery --" else "Battery ${bat.percent}% (${bat.millivolts} mV)") +
                        if (id != null && id.ppgCount > 1) "   |   Hub: reading sensor 0 only" else "",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TiltIndicator(
                    read = { session.tilt },
                    bubbleColor = PhysDaqColors.Pink,
                    ringColor = PhysDaqColors.Overlay
                )
                LiveChart("PPG, filtered (last ~10 s)", session.ppgFiltered)
                LiveChart("IR, raw counts", session.irRaw)
                Text(
                    describeStats(snap),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    @Composable
    private fun Header() {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_physdaq_heart),
                contentDescription = "PhysDAQ",
                tint = PhysDaqColors.Pink,
                modifier = Modifier.size(44.dp)
            )
            Column {
                Text("PhysDAQ", style = MaterialTheme.typography.titleLarge)
                Text(
                    "BODY SURFACE SENSOR ARRAY INTERFACE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    @Composable
    private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        }
    }

    @Composable
    private fun SectionLabel(text: String) {
        Text(
            text.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    /** mm:ss since Record was pressed (0:00 when idle). */
    @Composable
    private fun recordTimerText(active: Boolean): String {
        var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(active) {
            while (active) {
                now = SystemClock.elapsedRealtime()
                delay(500)
            }
        }
        if (!active || recordStartMs == 0L) return "00:00"
        val s = ((now - recordStartMs) / 1000).coerceAtLeast(0)
        return if (s >= 3600) {
            String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60)
        }
    }

    @Composable
    private fun MapLegend() {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(
                PhysDaqColors.Green to "Worn",
                PhysDaqColors.Amber to "Not worn",
                PhysDaqColors.Mauve to "Connecting",
                PhysDaqColors.Overlay to "Off / empty"
            ).forEach { (c, label) ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    /** Same meaning as the desktop body map: green worn, amber not worn, mauve connecting, grey off. */
    private fun statusColor(conn: BleConnectionState, worn: Boolean): Color {
        if (conn is BleConnectionState.Connected) {
            return if (worn) PhysDaqColors.Green else PhysDaqColors.Amber
        }
        val l = conn.label().lowercase(Locale.ROOT)
        return when {
            l.startsWith("fail") -> PhysDaqColors.Red
            l.startsWith("disconnect") -> PhysDaqColors.Overlay
            l.contains("connect") || l.contains("wait") || l.contains("scan") -> PhysDaqColors.Mauve
            else -> PhysDaqColors.Overlay
        }
    }

    @Composable
    private fun HeartRateRow(h: HeartRateState) {
        val bpm = h.bpm
        val beating = h.contact && bpm != null
        val big = if (!beating) "--" else bpm!!.toInt().toString()
        val note = when {
            !h.contact -> "Not worn: place the sensor on skin"
            bpm == null -> "Worn: needs about 8 s of steady contact"
            else -> "Worn"
        }

        // Like the desktop's heartbeat animation; a fixed pulse, not locked to the measured rate.
        val pulse = rememberInfiniteTransition(label = "beat")
        val animated by pulse.animateFloat(
            initialValue = 1f,
            targetValue = 1.22f,
            animationSpec = infiniteRepeatable(
                animation = tween(450, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "beatScale"
        )
        val scale = if (beating) animated else 1f

        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_physdaq_heart),
                contentDescription = null,
                tint = if (beating) PhysDaqColors.Pink else PhysDaqColors.Overlay,
                modifier = Modifier
                    .size(32.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
            )
            Text(big, style = MaterialTheme.typography.displayMedium)
            Column {
                Text("BPM", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!h.contact) PhysDaqColors.Amber else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    private fun kindLabel(d: ScannedDevice): String {
        val info = d.info ?: return "PhysDAQ"
        return when (info.kind) {
            DeviceKind.NODE -> "Node - ${info.ppgCount} PPG"
            DeviceKind.HUB -> "Hub - ${info.ppgCount} PPG" + if (info.hasSd) " + SD" else ""
            DeviceKind.UNKNOWN -> "PhysDAQ"
        }
    }

    private fun describeStats(s: StreamStats.Snapshot): String =
        if (s.samples == 0L || s.meanMs == 0.0) {
            "Stream: no samples yet"
        } else {
            String.format(
                Locale.US,
                "Stream: %.1f Hz | gap mean %.1f ms, std %.1f, max %.0f | bunched %.0f%% | %d samples",
                s.rateHz, s.meanMs, s.stdMs, s.maxMs, s.burstPct, s.samples
            )
        }

    private fun describeFile(f: FileStatus): String {
        val name = NodePosition.fromSlug(f.label)?.title ?: f.label
        val r = f.state
        return when {
            r.error != null -> "$name: recording error: ${r.error}"
            r.recording -> "$name: recording, ${r.rows} rows -> ${r.location ?: "..."}"
            r.location != null -> "$name: saved ${r.rows} rows -> ${r.location}"
            else -> "$name: starting..."
        }
    }
}
