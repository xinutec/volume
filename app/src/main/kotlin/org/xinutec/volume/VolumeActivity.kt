package org.xinutec.volume

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import org.xinutec.volume.protocol.AncMode
import org.xinutec.volume.protocol.AutoOff
import org.xinutec.volume.protocol.BoseBands
import org.xinutec.volume.protocol.BoseButton
import org.xinutec.volume.protocol.DeviceCard
import org.xinutec.volume.protocol.DeviceState
import org.xinutec.volume.protocol.Emptiness
import org.xinutec.volume.protocol.EqCurve
import org.xinutec.volume.protocol.JBL_CURVES
import org.xinutec.volume.protocol.JBL_EQ_PRESETS
import org.xinutec.volume.protocol.NoteKind
import org.xinutec.volume.protocol.Screen
import org.xinutec.volume.protocol.SettingKind
import org.xinutec.volume.protocol.Settings
import org.xinutec.volume.protocol.SoundQuality
import org.xinutec.volume.protocol.TimedOff
import java.util.Locale

/**
 * The app: every headphone that is bonded and drivable, and its ANC.
 *
 * Separate from [MainActivity], which stays the #783 probe. They share `:protocol`
 * and the transports, so a byte fixed in one is fixed in both — and the probe keeps
 * working, which matters because it is the only tool that can investigate a device
 * this screen cannot drive.
 *
 * ⚠ Everything here renders [Screen]; none of it decides. Which sentence a device
 * deserves — unreachable, unidentified, driven-but-unconfirmable — is worked out in
 * `:protocol` where it is tested, because those distinctions are exactly what a
 * look at the screen would not catch.
 */
class VolumeActivity : ComponentActivity() {
    private var screen by mutableStateOf(Screen(emptyList(), Emptiness.LOOKING))
    private lateinit var control: DeviceController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        control =
            DeviceController(this, adapter) { next ->
                runOnUiThread { screen = next }
            }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                VolumeScreen(
                    screen = screen,
                    onConnect = control::connect,
                    onSet = control::set,
                    actions = control,
                )
            }
        }
        control.refresh()
    }

    /**
     * Follow the radio while we are on screen.
     *
     * ⚠ Without this the list is a snapshot from launch: switch a pair off and its
     * card stays, offering modes over a socket that is gone. Registered in
     * [onStart] rather than [onCreate] so a backgrounded app is not opening sockets
     * to headphones nobody is looking at.
     */
    override fun onStart() {
        super.onStart()
        val filter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                // ⚠ **The profile events are the load-bearing ones; ACL alone finds
                // nothing.** Presence is read from the A2DP and headset proxies, and
                // those populate well after the link comes up. Measured on the Pixel
                // 9, 2026-08-16, switching the Sony on with this screen in front —
                // the timings are in `docs/liveness.md`:
                //
                //   53.768  ACL_CONNECTED           → proxies say connected=0
                //   54.992  profile state changed   → proxies say connected=1
                //
                // So an ACL-only listener queries 1.24 s too early, gets an empty
                // set, and NO FURTHER ACL EVENT EVER ARRIVES to correct it — which is
                // the four-minute disappearance this was written for. Going the other
                // way the profile events lead ACL_DISCONNECTED by 414 ms, so they are
                // the earlier signal in both directions, not a late repair to ACL.
                //
                // ⚠ Do not conclude from that four-minute note that this receiver
                // fixes a COLD start: nothing broadcasts for a pair that was already
                // connected when the app launched. `onStart`'s refresh below is what
                // covers that, and it is a separate mechanism. `adb logcat -s
                // VolumeLive` re-derives all of this in one connect.
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            }
        registerReceiver(links, filter)
        control.refresh()
    }

    /**
     * Off screen: stop following the radio, and **give the channels back**.
     *
     * ⚠ **This is the mechanism that matters**, not the idle lease. Until it existed,
     * a backgrounded Volume kept a live control link to every connected pair until
     * Android got round to destroying the activity — which can be many minutes, or
     * never. An app nobody is looking at has no business holding five radio links.
     *
     * The cards stay on screen; only the links go, and coming back re-opens them.
     */
    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(links) }
        control.release()
    }

    private val links =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device =
                    IntentCompat.getParcelableExtra(
                        intent,
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java,
                    )
                // ⚠ Logged because the ordering is the whole question. Each refresh
                // logs what the profile proxies then said, so the pair of lines shows
                // whether an ACL event alone would have found anything.
                Log.i(LIVE, "broadcast: ${intent.action?.substringAfterLast('.')}")
                // A profile going up or down does not invalidate an open session, so
                // only an ACL change drops one; the rest just re-ask who is here.
                val acl = intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED
                control.onLinkChanged(device?.address ?: return, dropSession = acl)
            }
        }

    override fun onDestroy() {
        super.onDestroy()
        control.closeAll()
    }
}

/**
 * What the settings section can ask for.
 *
 * An interface rather than eight lambdas threaded through three composables — and
 * `:app`-side, because unlike [Screen] it is plumbing rather than a decision.
 * [DeviceController] is the only implementation; a preview can pass an empty one.
 */
interface SettingActions {
    fun loadSettings(address: String)

    fun setEqPreset(address: String, preset: Int)

    fun setTone(address: String, bands: BoseBands)

    fun setMultipoint(address: String, on: Boolean)

    fun setAutoOff(address: String, mode: AutoOff)

    /** ⚠ Only the switch moves; the timeout is sent back as it was read. */
    fun setTimedOff(address: String, v: TimedOff)

    fun setCurve(address: String, curve: EqCurve)

    fun setSoundQuality(address: String, mode: SoundQuality)

    fun setButton(address: String, action: BoseButton.Action)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VolumeScreen(
    screen: Screen,
    onConnect: (String) -> Unit,
    onSet: (String, AncMode) -> Unit,
    actions: SettingActions,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Volume") }) }) { pad ->
        screen.emptiness?.let { why ->
            Column(
                modifier = Modifier.fillMaxSize().padding(pad).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (why == Emptiness.LOOKING) {
                    CircularProgressIndicator(Modifier.padding(bottom = 16.dp))
                }
                // ⚠ `textAlign`, not just the Column's `horizontalAlignment`: the
                // Text fills the width, so centring the block is a no-op and every
                // one of these wraps to two lines. Caught by looking — the code read
                // as centred and rendered hard left.
                Text(
                    reason(why),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(screen.cards, key = { it.address }) { card ->
                DeviceRow(card, onConnect, onSet, actions)
            }
        }
    }
}

@Composable
private fun DeviceRow(
    card: DeviceCard,
    onConnect: (String) -> Unit,
    onSet: (String, AncMode) -> Unit,
    actions: SettingActions,
) {
    // ⚠ Per card and remembered by address, so a refresh — which happens every time
    // any pair connects or disconnects — does not fold an open section shut under
    // someone mid-adjustment.
    var expanded by rememberSaveable(card.address) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // ⚠ The bonded name, and it may be anything: this phone's QC35 is called
            // "LE-Pippijn Headphon". The model we worked out goes underneath rather
            // than replacing it, so the owner can still tell which pair this is.
            Text(
                card.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when (val s = card.state) {
                is DeviceState.Idle -> {
                    Text(
                        "Not connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is DeviceState.Busy -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.padding(2.dp))
                        Text(s.what, style = MaterialTheme.typography.bodySmall)
                    }
                }

                is DeviceState.Unavailable -> {
                    Text(
                        s.why,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                is DeviceState.Ready -> {
                    Text(
                        s.model,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Only ever present when something is off — a confirmed write
                    // says nothing here, because the selected chip already says it.
                    s.note?.let {
                        Text(
                            it.text,
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                when (it.kind) {
                                    NoteKind.PROBLEM -> MaterialTheme.colorScheme.error
                                    NoteKind.CAUTION -> MaterialTheme.colorScheme.tertiary
                                },
                        )
                    }
                }
            }

            val modes = card.offer
            if (modes.isNotEmpty()) {
                // ⚠ Wraps rather than scrolls: three chips fit a narrow phone, four
                // (with TalkThru) would not, and a row that scrolls sideways hides
                // the mode you want behind a gesture nobody looks for.
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val current = (card.state as? DeviceState.Ready)?.mode
                    for (m in modes) {
                        FilterChip(
                            selected = m == current,
                            onClick = { onSet(card.address, m) },
                            label = { Text(label(m)) },
                        )
                    }
                }
            } else if (card.state is DeviceState.Idle || card.state is DeviceState.Unavailable) {
                FilterChip(
                    selected = false,
                    onClick = { onConnect(card.address) },
                    label = { Text("Connect") },
                )
            }

            // Settings hang off a Ready card only: there is nothing to read over a
            // link that is not open, and offering the row would promise otherwise.
            val ready = card.state as? DeviceState.Ready
            if (ready != null) {
                TextButton(
                    onClick = {
                        expanded = !expanded
                        if (expanded && card.settings == null) actions.loadSettings(card.address)
                    },
                    contentPadding =
                        androidx.compose.foundation.layout
                            .PaddingValues(0.dp),
                ) {
                    Text(if (expanded) "Hide settings" else "Settings")
                }
                if (expanded) {
                    // ⚠ A safety net, not the mechanism: [DeviceCard.settings] now
                    // survives the Busy transitions that used to wipe it, and this
                    // catches any other path that leaves the section open with
                    // nothing — which is otherwise a spinner that never resolves,
                    // because the read is triggered by opening the section.
                    LaunchedEffect(card.address, card.settings == null) {
                        if (card.settings == null) actions.loadSettings(card.address)
                    }
                    SettingsSection(card.address, card.settings, actions)
                }
            }
        }
    }
}

/**
 * Everything a device has beyond ANC.
 *
 * ⚠ **A setting the device refuses is drawn as a value, not a control.** The XM4
 * reports its multipoint and its [CUSTOM] button and then ignores writes to both —
 * measured, and Sony's own app fails the same way — so a switch here would flip and
 * spring back, which is this repo's oldest trap wearing a new hat. The value is still
 * worth showing; the control is not.
 */
@Composable
private fun SettingsSection(address: String, settings: Settings?, actions: SettingActions) {
    if (settings == null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.padding(2.dp))
            Text("reading…", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    if (!settings.any) {
        Text(
            "Nothing beyond noise cancelling is decoded for this pair yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        settings.eq?.let { eq ->
            // ⚠ The preset id is opaque and the vendor's names for it were never
            // captured, so it is shown as a number rather than given an invented
            // name. The levels underneath are the part that means something.
            SettingLabel("Equaliser", "preset ${eq.preset} · ${eq.levels.joinToString(", ")} dB")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (p in SONY_PRESETS) {
                    FilterChip(
                        selected = p == eq.preset,
                        onClick = { actions.setEqPreset(address, p) },
                        label = { Text("preset $p") },
                    )
                }
            }
            if (settings.bands.isNotEmpty()) {
                Text(
                    "bands: ${settings.bands.joinToString(" · ") { hz(it) }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        settings.tone?.let { t ->
            SettingLabel(
                "Tone",
                "bass ${signed(t.bass)} · mid ${signed(t.mid)} · treble ${signed(t.treble)} dB",
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Bose Music's own buttons, with its own numbers — these are the
                // app's presets, not the device's, so they are three band writes.
                for ((name, bands) in BOSE_TONE) {
                    FilterChip(
                        selected = bands == t,
                        onClick = { actions.setTone(address, bands) },
                        label = { Text(name) },
                    )
                }
            }
        }

        settings.curve?.let { c ->
            // ⚠ The name is looked up, not stored: the device sends back ten numbers
            // and a table id, and "Jazz" is only true if both still match what the app
            // sent for it. ⚠ **The table id is part of that, and it caught something**
            // — the JBL was found holding flat gains under a table neither chip
            // writes, so "custom" is shown with the id rather than a bare word that
            // would look like a rendering fault above ten zeroes.
            SettingLabel(
                "Equaliser",
                JBL_CURVES.firstOrNull { it.second == c }?.first
                    ?: "custom · ${JBL_EQ_PRESETS.getOrNull(c.table) ?: "table ${c.table}"}",
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((name, preset) in JBL_CURVES) {
                    FilterChip(
                        selected = preset == c,
                        onClick = { actions.setCurve(address, preset) },
                        label = { Text(name) },
                    )
                }
            }
            Text(
                // ⚠ **A non-breaking space, and it is load-bearing at this width.**
                // With an ordinary one the wrap fell between `16k` and its `0`, so the
                // last band's gain started the next line looking like a stray digit —
                // seen in the split-screen render, not in any test. Breaking only at
                // the separators keeps every band with its own number.
                c.bands.joinToString(" · ") { "${hz(it.hz)}\u00A0${db(it.gain)}" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        settings.timedOff?.let { v ->
            SettingRow(
                "Power off when idle",
                if (v.on) "after ${v.minutes} minutes" else "off",
                writable = true,
                checked = v.on,
                onChange = { actions.setTimedOff(address, v.copy(on = it)) },
            )
        }

        settings.volumeLimit?.let { on ->
            // ⚠ A value with no switch, and a sentence saying why — otherwise a
            // missing control reads as a missing feature, which is the trap
            // `RefusedNote` exists for. This one is not refused: the device would
            // take the write and its own app makes it freely.
            SettingLabel("Volume limit", if (on) "on" else "off")
            Text(
                "hearing protection — this app will read it, never change it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        settings.multipoint?.let { on ->
            SettingRow(
                "Two devices at once",
                if (on) "on" else "off",
                writable = settings.writable(SettingKind.MULTIPOINT),
                checked = on,
                onChange = { actions.setMultipoint(address, it) },
            )
        }

        settings.autoOff?.let { mode ->
            SettingLabel("Power off", autoOffLabel(mode))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in AutoOff.entries) {
                    FilterChip(
                        selected = m == mode,
                        onClick = { actions.setAutoOff(address, m) },
                        label = { Text(autoOffLabel(m)) },
                    )
                }
            }
        }

        settings.soundQuality?.let { mode ->
            SettingLabel("Sound quality", qualityLabel(mode))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in SoundQuality.entries) {
                    FilterChip(
                        selected = m == mode,
                        onClick = { actions.setSoundQuality(address, m) },
                        label = { Text(qualityLabel(m)) },
                    )
                }
            }
            // ⚠ Said before it is tapped, not after: this one drops the link and
            // brings it back, which without warning reads as the app crashing.
            Text(
                "changing this reconnects the headphones",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        settings.button?.let { current ->
            SettingLabel("Button", prettyAction(current))
            if (settings.writable(SettingKind.BUTTON)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (a in BoseButton.Action.entries) {
                        FilterChip(
                            selected = a.name == current,
                            onClick = { actions.setButton(address, a) },
                            label = { Text(prettyAction(a.name)) },
                        )
                    }
                }
            } else {
                RefusedNote()
            }
        }
    }
}

@Composable
private fun SettingLabel(title: String, value: String) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A switch, or the value with the reason there is no switch. */
@Composable
private fun SettingRow(
    title: String,
    value: String,
    writable: Boolean,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // ⚠ `weight(1f)` — without it the label column takes its full intrinsic
        // width and pushes the switch off a narrow screen entirely.
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!writable) RefusedNote()
        }
        if (writable) {
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

/** ⚠ The one sentence that keeps a missing control from reading as a missing feature. */
@Composable
private fun RefusedNote() {
    Text(
        "this pair will not let anything change it — not even its own app",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.tertiary,
    )
}

/**
 * ⚠ Preset ids only, no invented names. These are the ones seen on the wire
 * (`docs/sony-settings.md`); the XM4's menu holds more and nothing captured
 * enumerates them, so a fuller list would be guesswork rendered as fact.
 */
private val SONY_PRESETS = listOf(0xa0, 0xa1, 0xa2)

/** Bose Music's four buttons, with the numbers it actually sends. */
private val BOSE_TONE =
    listOf(
        "Flat" to BoseBands(0, 0, 0),
        "Bass boost" to BoseBands(bass = 8, mid = 0, treble = 0),
        "Treble boost" to BoseBands(bass = 0, mid = 0, treble = 6),
    )

internal fun signed(v: Int) = if (v > 0) "+$v" else "$v"

/**
 * A gain, as an equaliser draws it: `+4`, `−1.5`, `0`.
 *
 * ⚠ **Formatted without a locale's decimal comma**, because the wire is IEEE floats
 * and the vendor app's own axis is labelled with points. A band reading `+2,5` beside
 * a frequency reading `2.5k` is the kind of inconsistency that looks like a bug in
 * the decode rather than in the formatting.
 */
internal fun db(v: Float): String {
    val whole = v.toInt()
    val text = if (v == whole.toFloat()) "$whole" else String.format(Locale.ROOT, "%.1f", v)
    return if (v > 0) "+$text" else text
}

/**
 * A band centre as the vendor app labels it: `400`, `1k`, `2.5k`, `16k`.
 *
 * ⚠ **This rendered `2.5kk` on the first look at it**, from a `removeSuffix(".0k")`
 * that only fired on whole thousands followed by an unconditional `+ "k"`. Every
 * exact multiple came out right and the two in between did not, which is why the
 * arithmetic is now integer and why [VolumeFormatTest] names all five real bands.
 */
internal fun hz(v: Int): String =
    when {
        v < 1000 -> "$v"
        v % 1000 == 0 -> "${v / 1000}k"
        else -> "${v / 1000}.${v % 1000 / 100}k"
    }

private fun autoOffLabel(m: AutoOff) =
    when (m) {
        AutoOff.NEVER -> "Never"
        AutoOff.WHEN_REMOVED -> "When taken off"
    }

private fun qualityLabel(m: SoundQuality) =
    when (m) {
        SoundQuality.QUALITY -> "Quality (LDAC)"
        SoundQuality.STABLE -> "Stable connection"
    }

/** `HEAR_BATTERY_LEVEL` is not a thing to show anyone. */
private fun prettyAction(name: String) =
    name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

/**
 * What an empty list means, in words.
 *
 * ⚠ Each of these used to be "No headphones bonded to this phone", and four of
 * them were false. They are worded to name the thing the owner can act on — the
 * radio, the permission, the switch on the headphones — rather than to describe
 * the app's own state, which is what the old sentence did.
 */
fun reason(e: Emptiness): String =
    when (e) {
        Emptiness.LOOKING -> "Looking for your headphones…"
        Emptiness.NO_ADAPTER -> "This phone has no Bluetooth."
        Emptiness.BLUETOOTH_OFF -> "Bluetooth is off."
        Emptiness.NOT_PERMITTED -> "Volume needs Bluetooth permission to see your headphones."
        Emptiness.NONE_BONDED -> "No headphones paired with this phone yet."
        Emptiness.NONE_CONNECTED -> "No headphones switched on. Turn a pair on and it appears here."
        Emptiness.NONE_DRIVABLE -> "What is connected is not something this app can drive."
    }

/**
 * The vendors' own words, not the enum's.
 *
 * Public because `:protocol` takes it as a parameter: the decision of *whether* to
 * say something is tested there, and the wording lives here.
 */
fun label(m: AncMode): String =
    when (m) {
        AncMode.OFF -> "Off"
        AncMode.ANC -> "Noise cancelling"
        AncMode.AMBIENT -> "Ambient"
        AncMode.TALK_THRU -> "TalkThru"
    }
