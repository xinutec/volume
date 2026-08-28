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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import org.xinutec.volume.protocol.AncMode
import org.xinutec.volume.protocol.AutoOff
import org.xinutec.volume.protocol.Balance
import org.xinutec.volume.protocol.Battery
import org.xinutec.volume.protocol.BoseBands
import org.xinutec.volume.protocol.BoseButton
import org.xinutec.volume.protocol.BoseCncModes
import org.xinutec.volume.protocol.BosePromptName
import org.xinutec.volume.protocol.BoseStandbyTimer
import org.xinutec.volume.protocol.BoseVoicePromptLanguage
import org.xinutec.volume.protocol.ChatDetail
import org.xinutec.volume.protocol.ChatSensitivity
import org.xinutec.volume.protocol.DeviceCard
import org.xinutec.volume.protocol.DeviceState
import org.xinutec.volume.protocol.Emptiness
import org.xinutec.volume.protocol.EqCurve
import org.xinutec.volume.protocol.EqSetting
import org.xinutec.volume.protocol.Gesture
import org.xinutec.volume.protocol.GestureAction
import org.xinutec.volume.protocol.JBL_CURVES
import org.xinutec.volume.protocol.JBL_EQ_PRESETS
import org.xinutec.volume.protocol.JBL_IDLE_MINUTES
import org.xinutec.volume.protocol.ModeOutTime
import org.xinutec.volume.protocol.NoteKind
import org.xinutec.volume.protocol.RefusalReason
import org.xinutec.volume.protocol.Screen
import org.xinutec.volume.protocol.SettingKind
import org.xinutec.volume.protocol.Settings
import org.xinutec.volume.protocol.SidetoneLevel
import org.xinutec.volume.protocol.SmartAv
import org.xinutec.volume.protocol.SmartTalk
import org.xinutec.volume.protocol.SonyEq
import org.xinutec.volume.protocol.SoundQuality
import org.xinutec.volume.protocol.Spatial
import org.xinutec.volume.protocol.SpatialMode
import org.xinutec.volume.protocol.TalkTimeout
import org.xinutec.volume.protocol.TimedOff
import org.xinutec.volume.protocol.VoiceAware
import org.xinutec.volume.protocol.VoiceLevel
import java.util.Locale
import kotlin.math.roundToInt

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

    /**
     * Move the bands of whichever preset is selected.
     *
     * ⚠ **All six go every time** — the frame carries the whole curve and has no
     * field selector, so there is no such thing as writing one band.
     */
    fun setEqLevels(address: String, levels: List<Int>)

    fun setTone(address: String, bands: BoseBands)

    fun setMultipoint(address: String, on: Boolean)

    fun setAutoOff(address: String, mode: AutoOff)

    /** ⚠ Only the switch moves; the timeout is sent back as it was read. */
    fun setTimedOff(address: String, v: TimedOff)

    /** Bose's standby timer, in minutes; ⚠ `0` is the device's "never". */
    fun setStandby(address: String, minutes: Int)

    /** Select one of the QC45's named ANC modes by its slot. */
    fun setCncMode(address: String, slot: Int)

    /**
     * Move a QC45 mode's level on its eleven-point scale, `0` quietest.
     *
     * ⚠ **Called on release, not while dragging** — see the implementation.
     */
    fun setCncLevel(address: String, slot: Int, level: Int)

    /** Fill a free QC45 mode slot with one of the vendor's names. */
    fun createCncMode(address: String, slot: Int, name: BosePromptName, level: Int)

    /**
     * Empty a QC45 mode slot.
     *
     * ⚠ **Destructive and there are only four**, two of them built in. The driver
     * refuses a slot the device does not call editable.
     */
    fun deleteCncMode(address: String, slot: Int)

    /**
     * Bose's "Connect new" — put the headphones in pairing mode.
     *
     * ⚠ One-shot, with no "stop": leaving pairing mode is presumably payload `00` and
     * has never been sent. The mode times out on its own.
     */
    fun startPairing(address: String)

    /** ⚠ Refused for a CONNECTED device — see [Forget.Connected]. */
    fun forgetDevice(address: String, device: String)

    /** Rename the headphones — Bose Connect's "Nickname It". */
    fun setName(address: String, name: String)

    /** ⚠ Carries the prompt LANGUAGE across — it shares the byte. */
    fun setVoicePrompts(address: String, on: Boolean)

    /** ⚠ Carries the on/off state across, for the same reason. */
    fun setPromptLanguage(address: String, language: BoseVoicePromptLanguage)

    fun setSelfVoice(address: String, level: SidetoneLevel)

    /** ⚠ A refused action CLEARS the binding — see `Drivers.JblBes.writeGesture`. */
    fun setGesture(address: String, g: Gesture, want: GestureAction)

    fun setCurve(address: String, curve: EqCurve)

    /** ⚠ Switch and mode together — [Spatial] says why they cannot be sent apart. */
    fun setSpatial(address: String, v: Spatial)

    /** Likewise switch and level; see [VoiceAware]. */
    fun setVoiceAware(address: String, v: VoiceAware)

    /** Likewise switch and hold; see [SmartTalk]. */
    fun setSmartTalk(address: String, v: SmartTalk)

    /** ⚠ A plain switch — the only JBL row here that carries nothing alongside it. */
    fun setLowVolumeEq(address: String, on: Boolean)

    /** DSEE Extreme; `true` is `AUTO`. */
    fun setDsee(address: String, on: Boolean)

    fun setPauseOnRemoval(address: String, on: Boolean)

    fun setSpeakToChat(address: String, on: Boolean)

    fun setTouchPanel(address: String, on: Boolean)

    /**
     * ⚠ **Switching this ON can make the headphones speak.** That is the setting doing
     * its job, not a side effect — but it is the one control here that is audible to
     * whoever is wearing them.
     */
    fun setVoiceGuidance(address: String, on: Boolean)

    /**
     * ⚠ **Ends the session, and only a hand can undo it.** Every other action here writes
     * a setting that can be written back; this one switches the headphones off, and they
     * come back only by pressing their own button. The screen confirms before calling it.
     */
    fun powerOff(address: String)

    fun setChatDetail(address: String, detail: ChatDetail)

    fun setSonyButton(address: String, name: String)

    fun answerButton(address: String, yes: Boolean)

    /** ⚠ Ambient mode only — the UI offers it only when the device is there. */
    fun setFocusOnVoice(address: String, on: Boolean)

    /** ⚠ Three states and no switch; [SmartAv] says why off is one of them. */
    fun setSmartAv(address: String, v: SmartAv)

    fun setAutoPlay(address: String, on: Boolean)

    /** ⚠ Only the switch moves; the level is sent back as it was read. */
    fun setBalance(address: String, v: Balance)

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
    // ⚠ **Which sections are open lives HERE — above the Scaffold, above the empty-list
    // branch, and outside the LazyColumn item.** Three enclosures, and all three matter:
    //
    //  - the ITEM is removed when a card leaves `screen.cards`, taking any `remember`
    //    with it however it is keyed;
    //  - the LIST goes when [Screen.emptiness] takes the early return below — which is
    //    what happens when the only headphone disconnects;
    //  - so only state outside both survives a device going away and coming back.
    //
    // ⚠ Not hypothetical, and the first fix for it was put inside the list and did NOT
    // work: committing a [CUSTOM] button change drops the link on purpose, and the
    // section still shut itself the moment the card vanished. #1136.
    //
    // ⚠ Distinct from #973, which was the card SHRINKING so the scroll offset was
    // clamped. Keeping the card the same height fixed that and does nothing here, where
    // the card is gone entirely.
    val openSections = rememberSaveable { mutableStateListOf<String>() }
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
                DeviceRow(card, onConnect, onSet, actions, openSections)
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
    openSections: MutableList<String>,
) {
    // ⚠ Owned by the caller, for the reason given where it is declared: a card that
    // disconnects leaves the list, and anything remembered in here goes with it.
    val expanded = card.address in openSections
    // ⚠ **The DEVICE is asking, and this forwards the question rather than answering it.**
    // The XM4 will not commit a key-assign change until it is answered, and yes drops the
    // audio link — so it is the owner's call, not a switch's. See [DeviceCard.asking].
    var confirmOff by remember(card.address) { mutableStateOf(false) }
    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text("Switch off ${card.name}?") },
            // ⚠ Says what it COSTS, not what it does. "Turn off" is obvious; that the app
            // cannot turn them back on is the part someone needs before tapping.
            text = { Text("They can only be switched back on by hand, on the headphones.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmOff = false
                    actions.powerOff(card.address)
                }) { Text("Switch off") }
            },
            dismissButton = {
                TextButton(onClick = { confirmOff = false }) { Text("Cancel") }
            },
        )
    }
    card.asking?.let { question ->
        AlertDialog(
            onDismissRequest = { actions.answerButton(card.address, false) },
            title = { Text(card.name) },
            text = { Text(question) },
            confirmButton = {
                TextButton(onClick = { actions.answerButton(card.address, true) }) {
                    Text("Change it")
                }
            },
            dismissButton = {
                TextButton(onClick = { actions.answerButton(card.address, false) }) {
                    Text("Leave it")
                }
            },
        )
    }
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
                    // ⚠⚠ **The spinner is sized to the TEXT LINE, and that is load-bearing
                    // — it is the whole of #1203.** A default CircularProgressIndicator is
                    // 40.dp where the Ready branch below is one `bodySmall` line, so going
                    // Busy made this row ~24.dp taller and pushed EVERY control under it
                    // down the screen. Measured on a Pixel 9: the ANC chips moved from
                    // y=2037 to y=2110, **73 px**, within a frame of the first tap.
                    //
                    // That is what "a chip tap during a write does nothing" actually was.
                    // The gesture was never swallowed: the second tap landed where the
                    // chip had been and hit empty card. Proved by aiming the second tap at
                    // the SHIFTED position instead — two taps 75 ms apart, tighter than the
                    // failing case, and both fired. Three earlier hypotheses (alpha,
                    // composition identity, a pointer block) each changed nothing, because
                    // none of them was ever the cause.
                    //
                    // ⚠ So the rule is a HEIGHT rule, not a spinner rule: whatever this
                    // branch draws must occupy the same height as the Ready branch's first
                    // line, or the bug comes straight back.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
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

            // Settings hang off an OPEN link — Ready, or Busy doing something to it.
            //
            // ⚠ **Excluding Busy is what made the list jump to the top after every
            // write (#973).** A write goes `Ready → Busy → Ready`, and while Busy this
            // dropped the whole section: the card collapsed from a screenful to a
            // single spinner line, `LazyColumn` clamped the scroll offset to 0 because
            // there was no longer that much to scroll, and growing back did not restore
            // it. Keys were not the cause and stable ones did not help — the content
            // height was. Keeping the section rendered keeps the card the same size
            // across the transition, so there is nothing to clamp.
            //
            // ⚠ It renders from [DeviceCard.settings], which already survives Busy for
            // exactly this reason. The values shown mid-write are the pre-write ones,
            // which is honest: the new value is not known until the refresh lands.
            val open = card.state is DeviceState.Ready || card.state is DeviceState.Busy
            if (open) {
                TextButton(
                    onClick = {
                        // ⚠ **Opening the section is ALL this does.** The read is started
                        // by the effect below, and starting it here as well is what made
                        // every card open run the whole read cycle twice — measured on
                        // the wire 2026-08-28, two GET_ALLs and two mode-table reads for
                        // one tap. Both triggers fired on the same condition at the same
                        // moment, so neither was ever the redundant-looking one. #1191.
                        if (expanded) {
                            openSections -= card.address
                        } else {
                            openSections += card.address
                        }
                    },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(if (expanded) "Hide settings" else "Settings")
                }
                if (expanded) {
                    // ⚠ **THE mechanism, and deliberately the only one.** It was added as
                    // a safety net beside the button's own call, on the reasoning that a
                    // section could be opened by some other path and then spin forever —
                    // but it fires on exactly the condition the button did, at the same
                    // moment, so it was never a net. It covers strictly more paths than
                    // the button did, so it is the one that stayed.
                    //
                    // ⚠ Keyed on `settings == null` so a completed read stops it and a
                    // failed one does not retry in a loop.
                    LaunchedEffect(card.address, card.settings == null) {
                        if (card.settings == null) actions.loadSettings(card.address)
                    }
                    // ⚠ **Dimmed while busy, because the spinner is at the TOP of the
                    // card and the row you tapped may be a screen below it.** Keeping
                    // the section rendered through a write took away the only feedback
                    // a tap used to give — the section vanishing. Without this a tap
                    // looks like nothing happened for the two seconds the write takes,
                    // which invites a second one.
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier =
                            Modifier.alpha(if (card.state is DeviceState.Busy) 0.4f else 1f),
                    ) {
                        SettingsSection(card.address, card.name, card.settings, actions) {
                            confirmOff = true
                        }
                    }
                }
            }
        }
    }
}

/**
 * The level Bose Music starts a new mode at — its slider opens at the midpoint, and the
 * device writes the same `05` into a slot it is blanking.
 */
private const val NEW_MODE_LEVEL = 5

/**
 * Everything a device has beyond ANC.
 *
 * ⚠ **A setting that will not move is drawn as a value, not a control.** The XM4
 * reports its multipoint and its [CUSTOM] button and then ignores writes to both, so a
 * switch here would flip and spring back — this repo's oldest trap wearing a new hat.
 * The value is still worth showing; the control is not.
 *
 * ⚠ **They do not fail for the same reason and the screen must not say they do.** This
 * comment used to read "and Sony's own app fails the same way" about both. That is true
 * of multipoint and false of the button, and the note rendered under them said so out
 * loud — see [RefusalReason].
 */

@Composable
private fun SettingsSection(
    address: String,
    /**
     * ⚠ The name as the CARD has it, not a copy inside [Settings]. One string, one place
     * to be wrong about it — the rename dialog seeds from what is on screen.
     */
    name: String,
    settings: Settings?,
    actions: SettingActions,
    onPowerOff: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<BoseCncModes.Mode?>(null) }
    var addingAt by remember { mutableStateOf<Int?>(null) }

    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${m.name}?") },
            // ⚠ Says what cannot be undone rather than "are you sure": the level and the
            // name go, and this app cannot restore a mode it did not record first.
            text = {
                Text(
                    "The headphones keep four mode slots and two are built in. " +
                        "Deleting ${m.name} frees its slot; its level of ${m.level} is not kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.deleteCncMode(address, m.slot)
                    confirmDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Keep it") }
            },
        )
    }

    addingAt?.let { slot ->
        AlertDialog(
            onDismissRequest = { addingAt = null },
            title = { Text("Add a mode") },
            // ⚠ The vendor's OWN ten for this product, not all 37 it knows: nothing here
            // has seen what a QC45 does with a name its app never sends — see OFFERED.
            text = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in BosePromptName.OFFERED) {
                        FilterChip(
                            selected = false,
                            onClick = {
                                // ⚠ Created at the midpoint the vendor app starts a new
                                // mode at; the slider then moves it.
                                actions.createCncMode(address, slot, n, NEW_MODE_LEVEL)
                                addingAt = null
                            },
                            label = { Text(n.label) },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { addingAt = null }) { Text("Cancel") }
            },
        )
    }

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
        // ⚠ **Two sentences, because empty means two different things.** This was one,
        // and it claimed the repo had decoded nothing for the pair — shown on a JBL
        // with six decoded settings whose reads had every one failed on a stale link.
        // `attempted` is what separates "nobody asked" from "nothing answered".
        if (settings.attempted) {
            Text(
                "Could not read this pair's settings — the link may have gone. " +
                    "Reconnect to try again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                "Nothing beyond noise cancelling is decoded for this pair yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        settings.eq?.let { eq ->
            // ⚠ The preset id is opaque and the vendor's names for it were never
            // captured, so it is shown as a number rather than given an invented
            // name. The levels underneath are the part that means something.
            SettingLabel("Equaliser", "preset ${eq.preset}")
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
            EqBands(address, eq, settings.bands, actions)
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
                if (v.on) "after ${idleLabel(v.minutes)}" else "off",
                writable = true,
                checked = v.on,
                onChange = { actions.setTimedOff(address, v.copy(on = it)) },
            )
            // ⚠ Offered while OFF, like Spatial's modes and for the same reason: the
            // device keeps the timeout across a switch-off, so hiding these would throw
            // the choice away on every toggle. Driven at the switch's own `off` on
            // 2026-08-25, which is also why changing one is inaudible.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in JBL_IDLE_MINUTES) {
                    FilterChip(
                        selected = m == v.minutes,
                        onClick = { actions.setTimedOff(address, v.copy(minutes = m)) },
                        label = { Text(idleLabel(m)) },
                    )
                }
            }
        }

        settings.spatial?.let { v ->
            SettingRow(
                "Spatial sound",
                if (v.on) v.mode.name.lowercase() else "off",
                writable = true,
                checked = v.on,
                onChange = { actions.setSpatial(address, v.copy(on = it)) },
            )
            // ⚠ **The chips do NOT switch it on, and the vendor app's do.** Tapping
            // Movie there sends `aa 9d 03 00 01 02` — mode and enable in one frame,
            // because that is the only frame the device has. Building the write here
            // means the enable byte can carry `v.on` instead, so choosing what it
            // should render for is not also a decision to turn it on. The mode is
            // offered while off for the same reason the device keeps it: it is
            // remembered, and `off` is not `no mode`.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in SpatialMode.entries) {
                    FilterChip(
                        selected = m == v.mode,
                        onClick = { actions.setSpatial(address, v.copy(mode = m)) },
                        label = { Text(m.name.lowercase()) },
                    )
                }
            }
        }

        settings.voiceAware?.let { v ->
            SettingRow(
                "VoiceAware",
                if (v.on) v.level.name.lowercase() else "off",
                writable = true,
                checked = v.on,
                onChange = { actions.setVoiceAware(address, v.copy(on = it)) },
            )
            // ⚠ Chips where the vendor app has a slider — deliberately. The device
            // takes three values, so a continuous bar offers a precision the wire does
            // not have, and it is the reason this level went undecoded for weeks: a
            // drag is the one gesture that cannot be automated, and reading the bar
            // told nobody which of three it had landed on.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (l in VoiceLevel.entries) {
                    FilterChip(
                        selected = l == v.level,
                        onClick = { actions.setVoiceAware(address, v.copy(level = l)) },
                        label = { Text(l.name.lowercase()) },
                    )
                }
            }
        }

        settings.smartTalk?.let { v ->
            SettingRow(
                "Smart Talk",
                if (v.on) "hold ${v.timeout.seconds} s" else "off",
                writable = true,
                checked = v.on,
                onChange = { actions.setSmartTalk(address, v.copy(on = it)) },
            )
            // The seconds are the wire value, so these labels cannot drift from what
            // the device is told — see [TalkTimeout]. Same rule as the chips above:
            // choosing a hold does not switch the feature on.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in TalkTimeout.entries) {
                    FilterChip(
                        selected = t == v.timeout,
                        onClick = { actions.setSmartTalk(address, v.copy(timeout = t)) },
                        label = { Text("${t.seconds} s") },
                    )
                }
            }
        }

        settings.lowVolumeEq?.let { on ->
            SettingRow(
                "Low volume dynamic EQ",
                if (on) "on" else "off",
                writable = true,
                checked = on,
                onChange = { actions.setLowVolumeEq(address, it) },
            )
        }

        settings.smartAv?.let { v ->
            // ⚠ No switch, deliberately — the device has no enable byte, so `off` is
            // one of three choices rather than the absence of the other two. The
            // vendor app draws a switch and a mode here and can therefore show
            // Video-and-off, a state the headphones never actually hold.
            SettingLabel("Smart audio & video", v.name.lowercase())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in SmartAv.entries) {
                    FilterChip(
                        selected = m == v,
                        onClick = { actions.setSmartAv(address, m) },
                        label = { Text(m.name.lowercase()) },
                    )
                }
            }
        }

        settings.autoPlay?.let { on ->
            SettingRow(
                "Auto play & pause",
                if (on) "on" else "off",
                writable = true,
                checked = on,
                onChange = { actions.setAutoPlay(address, it) },
            )
        }

        settings.balance?.let { v ->
            // ⚠ The level is carried, never offered — nothing here has ever moved it,
            // so its range is unknown and 100 is only known to be this unit's centre.
            SettingRow(
                "Left / right balance",
                if (v.on) "on, level ${v.level}" else "off",
                writable = true,
                checked = v.on,
                onChange = { actions.setBalance(address, v.copy(on = it)) },
            )
        }

        settings.psap?.let { on ->
            SettingLabel("Sound amplification", if (on) "on" else "off")
            Text(
                "amplifies the world — this app will read it, never change it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        settings.voicePrompts?.let { on ->
            SettingRow(
                "Voice prompts",
                settings.promptLanguage
                    ?.name
                    ?.lowercase()
                    ?.replace('_', ' ') ?: "",
                writable = true,
                checked = on,
                onChange = { actions.setVoicePrompts(address, it) },
            )
            // ⚠ The device's OWN list, not the enum: this unit speaks thirteen of the
            // twenty-two, and the missing ones are not guessable — UK English is absent
            // while US English is present. Offered while off, like the JBL's timeouts,
            // because the language survives the switch.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (l in settings.supportedLanguages) {
                    FilterChip(
                        selected = l == settings.promptLanguage,
                        onClick = { actions.setPromptLanguage(address, l) },
                        label = { Text(l.name.lowercase().replace('_', ' ')) },
                    )
                }
            }
        }

        if (settings.canRename) {
            var renaming by remember(address) { mutableStateOf(false) }
            // ⚠ The DEVICE's name, falling back to the bonded one only when it will not
            // say. Showing the bonded name here made a successful rename look like a
            // no-op — Android keeps its own record and this protocol does not touch it.
            val held = settings.deviceName ?: name
            SettingLabel("Name", held)
            TextButton(onClick = { renaming = true }) { Text("Rename") }
            if (renaming) {
                RenameDialog(
                    current = held,
                    onDismiss = { renaming = false },
                    onConfirm = {
                        renaming = false
                        actions.setName(address, it)
                    },
                )
            }
        }

        if (settings.devices.isNotEmpty() || settings.pairing != null) {
            SettingLabel("Connections", "")
            for (d in settings.devices) {
                // The NAME, with the address only as a fallback: a list of six-byte
                // addresses tells nobody which entry is their laptop.
                SettingLabel(
                    "  ${d.name ?: d.address}",
                    if (d.connected) "connected" else "paired",
                )
                // ⚠ Offered ONLY when it is not connected. Forgetting a live device
                // disconnects it as part of the same command, and the phone drawing this
                // card is always the connected one.
                if (!d.connected) {
                    TextButton(onClick = { actions.forgetDevice(address, d.address) }) {
                        Text("Forget")
                    }
                }
            }
            // ⚠ One button, no toggle. Leaving pairing mode has never been sent, and the
            // mode times out by itself — a "stop" here would be a guessed frame on the
            // block that holds CLEAR_DEVICE_LIST.
            TextButton(onClick = { actions.startPairing(address) }) {
                Text(if (settings.pairing == true) "Ready to connect" else "Connect new")
            }
        }

        settings.cnc?.let { cnc ->
            // ⚠ The device's OWN names — "Quiet", "Aware", and whatever the owner
            // called the ones they made. Nothing here supplies a label.
            SettingLabel("Noise control", cnc.current?.name ?: "unknown mode")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in cnc.modes) {
                    FilterChip(
                        selected = m.slot == cnc.active,
                        onClick = { actions.setCncMode(address, m.slot) },
                        label = { Text(m.name) },
                    )
                }
            }
            cnc.current?.let { m ->
                if (m.editable) {
                    // ⚠ Only the owner's modes get a slider: Quiet and Aware report
                    // themselves not editable and a write to them is unattested.
                    var level by
                        remember(m.slot, m.level) {
                            mutableFloatStateOf(m.level.toFloat())
                        }
                    SettingLabel("Level", "${level.toInt()} of ${BoseCncModes.MOST_AWARE}")
                    Slider(
                        value = level,
                        onValueChange = { level = it },
                        // ⚠ ON RELEASE. The vendor app sends one frame per position —
                        // eight for a single drag — and copying that would put a burst
                        // on the channel for every gesture.
                        onValueChangeFinished = {
                            actions.setCncLevel(address, m.slot, level.toInt())
                        },
                        valueRange =
                            BoseCncModes.QUIETEST.toFloat()..BoseCncModes.MOST_AWARE.toFloat(),
                        steps = BoseCncModes.MOST_AWARE - 1,
                    )
                    // ⚠ Only for a mode the DEVICE calls editable, which is the same
                    // guard the driver applies again on the list it reads in the call.
                    // Two independent refusals, because the order of this list moves.
                    TextButton(onClick = { confirmDelete = m }) {
                        Text("Delete ${m.name}", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            // ⚠ **A free slot cannot be found from the mode list** — an emptied slot
            // still answers with a full-length record. `cnc.free` reads `1f 08`.
            cnc.free?.let { slot ->
                TextButton(onClick = { addingAt = slot }) { Text("Add a mode") }
            }
        }

        settings.standby?.let { st ->
            SettingLabel("Standby timer", standbyLabel(st.minutes))
            // ⚠ The vendor app's own six values and its own word for zero, not a free
            // number: every one of these was selected in Bose Connect and read back off
            // the wire, and the byte's edges are unprobed. See BoseStandbyTimer.OFFERED.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in BoseStandbyTimer.OFFERED) {
                    FilterChip(
                        selected = m == st.minutes,
                        onClick = { actions.setStandby(address, m) },
                        label = { Text(standbyLabel(m)) },
                    )
                }
            }
        }

        settings.selfVoice?.let { level ->
            SettingLabel("Self voice", level.name.lowercase())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (l in SidetoneLevel.entries) {
                    FilterChip(
                        selected = l == level,
                        onClick = { actions.setSelfVoice(address, l) },
                        label = { Text(l.name.lowercase()) },
                    )
                }
            }
        }

        settings.advancedAnc?.let { a ->
            SettingLabel("Customize ANC", a.tuning?.name?.lowercase() ?: "unknown tuning")
            // ⚠ Raw numbers with their key names, NOT sliders. Nothing establishes what
            // these are out of, and a slider draws a scale — it would answer a question
            // this repo has not asked the hardware.
            val detail =
                listOfNotNull(
                    a.manualLevel?.let { "manual level $it" },
                    a.ambientLevel?.let { "ambient level $it" },
                    a.leakageCompensation?.let { "leakage $it" },
                    a.autoCompensation?.let { "auto comp $it" },
                    a.earCanalCompensation?.let { "ear canal $it" },
                )
            if (detail.isNotEmpty()) {
                Text(
                    detail.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        settings.leAudio?.let { on ->
            SettingLabel("LE Audio", if (on) "on" else "off")
            // ⚠ **NOT the error colour.** That one is reserved for the two hearing rows,
            // where the sentence is a promise about the owner's ears. This is an ordinary
            // "we do not drive this yet", and dressing it in red would flatten the
            // difference between a rule and a gap.
            Text(
                "changing it renegotiates the audio link — read only for now",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        settings.auracast?.let { on ->
            SettingLabel("Auracast", if (on) "on" else "off")
        }

        settings.codec?.let { c ->
            // ⚠ A label, not a row with a control: nothing here can set a codec, and a
            // greyed switch would suggest the app merely refuses to.
            SettingLabel("Codec", c)
        }

        if (settings.canPowerOff) {
            // ⚠ **Last, and separated, because it is not a setting.** Everything above
            // reports something the device holds; this ends the session. Putting it in
            // the flow of switches would make it one more thing to flick past.
            // ⚠ Zero content padding, as the "Settings" link above does. A TextButton's
            // own inset pushed this one label past every other row's left edge — visible
            // only in a render, and the reason the card is looked at rather than reasoned
            // about.
            TextButton(
                onClick = { onPowerOff() },
                contentPadding = PaddingValues(0.dp),
            ) { Text("Switch off") }
        }

        settings.battery?.let { b ->
            SettingLabel(
                "Battery",
                // ⚠ `== true`, not truthiness: null means the device never said, and
                // saying nothing is right there — see [Battery.charging].
                if (b.charging == true) "${b.percent}%, charging" else "${b.percent}%",
            )
        }

        settings.gestures?.let { map ->
            SettingLabel(
                "Controls",
                "${map.count { it.value != GestureAction.NONE }} of ${map.size} assigned",
            )
            // ⚠ **The device decides, one action at a time, and there is no way to ask
            // in advance.** `aa 13` is analytics, not a capability list, and the vendor's
            // own `product_gesture_config.json` has no entry for this model — so the app
            // discovers the permitted set by trying, exactly as the vendor's does. What
            // makes that safe to offer is the restore in `Drivers.JblBes.writeGesture`,
            // not any list held here. #1039.
            var editing by remember(address) { mutableStateOf<Gesture?>(null) }
            editing?.let { g ->
                GesturePicker(
                    gesture = g,
                    current = map[g],
                    onPick = {
                        editing = null
                        actions.setGesture(address, g, it)
                    },
                    onDismiss = { editing = null },
                )
            }
            for (g in Gesture.entries) {
                val action = map[g] ?: continue
                // ⚠ **`clickable`, not a TextButton.** A TextButton enforces a 48 dp
                // minimum height, and eight of them turned a compact list into a page of
                // sprawl — visible only in the render. The tap target is still generous
                // because the row spans the card's full width.
                Text(
                    "${g.label} — ${action.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { editing = g }
                            .padding(vertical = 6.dp),
                )
            }
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
                refusal = settings.refusal(SettingKind.MULTIPOINT),
            )
        }

        settings.dsee?.let { on ->
            SettingRow(
                "DSEE Extreme",
                if (on) "on" else "off",
                writable = settings.writable(SettingKind.DSEE),
                checked = on,
                onChange = { actions.setDsee(address, it) },
            )
        }

        settings.pauseOnRemoval?.let { on ->
            SettingRow(
                "Pause when removed",
                if (on) "on" else "off",
                writable = settings.writable(SettingKind.PAUSE_ON_REMOVAL),
                checked = on,
                onChange = { actions.setPauseOnRemoval(address, it) },
            )
        }

        settings.speakToChat?.let { on ->
            SettingRow(
                "Speak-to-Chat",
                if (on) "on" else "off",
                writable = settings.writable(SettingKind.SPEAK_TO_CHAT),
                checked = on,
                onChange = { actions.setSpeakToChat(address, it) },
            )
        }

        settings.chatDetail?.let { d ->
            // ⚠ **Three controls, one frame.** Each chip sends the whole [ChatDetail]
            // with one field changed — see [SonyChatDetail], where the payload has no
            // field selector, so a partial write would reset the other two.
            // ⚠ **Sony's own titles, and that is not pedantry here.** "Voice focus" was
            // the first label for [ChatDetail.voiceFocus] and it sat four rows above
            // "Focus on Voice", which is a DIFFERENT setting — `AsmId`, in ambient mode.
            // Two near-identical names for unrelated controls, on one screen. The source
            // read fine; only the render showed it. Sony calls this one "Voice
            // passthrough", which collides with nothing.
            //
            // ⚠ The TITLES are Sony's verbatim; the chip labels deliberately are not.
            // Sony's own options read "Automatic", "H Sensitivity", "L Sensitivity" —
            // upstream taxonomy is worth following for the name of a thing, not for a
            // three-way choice its own words make harder to read.
            SettingLabel("Voice Detect Sensitivity", d.sensitivity.name.lowercase())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (v in ChatSensitivity.entries) {
                    FilterChip(
                        selected = v == d.sensitivity,
                        onClick = { actions.setChatDetail(address, d.copy(sensitivity = v)) },
                        label = { Text(v.name.lowercase()) },
                    )
                }
            }
            SettingRow(
                "Voice passthrough",
                if (d.voiceFocus) "on" else "off",
                writable = true,
                checked = d.voiceFocus,
                onChange = { actions.setChatDetail(address, d.copy(voiceFocus = it)) },
                note = "filters in voices while suppressing noise",
            )
            // ⚠ Seconds from the device's own capability reply, not from a table here.
            SettingLabel(
                "Time until the mode closes",
                if (d.modeOutTime == ModeOutTime.NONE) {
                    "not until you tap"
                } else {
                    "${d.modeOutTime.seconds} s"
                },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (v in ModeOutTime.entries) {
                    FilterChip(
                        selected = v == d.modeOutTime,
                        onClick = { actions.setChatDetail(address, d.copy(modeOutTime = v)) },
                        label = { Text(if (v.seconds == 0) "never" else "${v.seconds} s") },
                    )
                }
            }
        }

        settings.touchPanel?.let { on ->
            // ⚠ **Sony's own words, shortened**: "control playback, adjust volume,
            // receive/end phone calls". The note is there because "Touch panel: off" does
            // not tell an owner that their taps are being ignored on purpose.
            SettingRow(
                "Touch sensor control panel",
                if (on) "on" else "off",
                writable = settings.writable(SettingKind.TOUCH_PANEL),
                checked = on,
                onChange = { actions.setTouchPanel(address, it) },
                note = "when off, the earcup ignores taps and swipes",
            )
        }

        settings.voiceGuidance?.let { on ->
            SettingRow(
                "Voice guidance",
                if (on) "on" else "off",
                writable = settings.writable(SettingKind.VOICE_GUIDANCE),
                checked = on,
                onChange = { actions.setVoiceGuidance(address, it) },
                note = "spoken prompts; switching it on may say so out loud",
            )
        }

        settings.focusOnVoice?.let { on ->
            // ⚠ Shown always, switchable only in ambient. The fourth distinct reason a
            // control is absent on this screen, and the sentence says which one it is —
            // a missing switch with no explanation reads as a missing feature.
            SettingRow(
                "Focus on Voice",
                if (on) "on" else "off",
                writable = settings.focusOnVoiceSettable,
                checked = on,
                onChange = { actions.setFocusOnVoice(address, it) },
                note =
                    if (settings.focusOnVoiceSettable) null else "switch to Ambient to change this",
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
            if (settings.buttonOptions.isNotEmpty()) {
                // ⚠ **The DEVICE'S list, never `SonyButton.Action.entries`.** The enum
                // contains `VOLUME_CONTROL`; this pair does not offer it, and building
                // chips from the enum would put a volume control on the card for a
                // device that never advertised one. See [Settings.buttonOptions].
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (name in settings.buttonOptions) {
                        FilterChip(
                            selected = name == current,
                            onClick = { actions.setSonyButton(address, name) },
                            label = { Text(prettyAction(name)) },
                        )
                    }
                }
            } else if (settings.writable(SettingKind.BUTTON)) {
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
                RefusedNote(settings.refusal(SettingKind.BUTTON))
            }
        }
    }
}

/**
 * One slider per equaliser level, labelled with the frequency it moves.
 *
 * ⚠ **SIX levels for FIVE bands.** The first is CLEAR BASS, which Sony's own app
 * draws as a separate control below the curve rather than as a sixth point on it.
 * Zipping [levels] straight against [bands] is off by one from the first entry
 * onward, which is why the names are built with clear bass prepended rather than by
 * indexing one list with the other's position.
 *
 * ⚠ **A drag sends nothing until it is released.** The frame carries the whole curve,
 * so every intermediate position would be a full six-band write down a channel that
 * takes about a second per exchange — Sony's own app emits ten for one gesture. The
 * value under the finger is local until [Slider.onValueChangeFinished].
 *
 * ⚠ **The scale is the vendor app's, not the device's.** No frame declares a range;
 * [SonyEq.RANGE] is read off Sound Connect's axis. It bounds what this offers, and it
 * is not evidence about what the headphones would refuse.
 */
@Composable
private fun EqBands(address: String, eq: EqSetting, bands: List<Int>, actions: SettingActions) {
    val names = listOf("clear bass") + bands.map(::hz)
    // Keyed on the setting itself: when a write lands — or is contradicted — the
    // device's own answer replaces whatever the finger left behind.
    var dragged by remember(eq) { mutableStateOf<List<Int>?>(null) }
    val shown = dragged ?: eq.levels
    shown.forEachIndexed { i, level ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                names.getOrElse(i) { "band ${i + 1}" },
                modifier = Modifier.width(76.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Slider(
                value = level.toFloat(),
                onValueChange = { v ->
                    dragged = shown.toMutableList().also { it[i] = v.roundToInt() }
                },
                onValueChangeFinished = { dragged?.let { actions.setEqLevels(address, it) } },
                valueRange = SonyEq.RANGE.first.toFloat()..SonyEq.RANGE.last.toFloat(),
                // One stop per whole dB, minus the two endpoints, which Slider counts
                // separately — 19 here, not 21. Off by one and the stops land between
                // the values the wire can carry.
                steps = SonyEq.RANGE.count() - 2,
                modifier = Modifier.weight(1f),
            )
            Text(
                signed(level),
                modifier = Modifier.width(36.dp),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.End,
            )
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
    refusal: RefusalReason? = null,
    note: String? = null,
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
            RefusedNote(refusal)
            // ⚠ Inside the row's Column, not after the row. Rendered as a sibling it
            // sat almost equidistant between its own value and the NEXT setting's
            // title, so "switch to Ambient to change this" read as if it were about
            // Power off. Caught by looking at the render, not by the dump — the text
            // was correct and in the right order either way.
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (writable) {
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

/** ⚠ The one sentence that keeps a missing control from reading as a missing feature. */
@Composable
private fun RefusedNote(reason: RefusalReason?) {
    // ⚠ Two sentences because there are two facts. One of them used to be said about
    // both, and was false about the button — see [RefusalReason].
    val text =
        when (reason) {
            RefusalReason.DEVICE -> {
                "this pair will not let anything change it — not even its own app"
            }

            RefusalReason.THIS_APP -> {
                "the headphones ignore this from us; their own app can still change it"
            }

            null -> {
                return
            }
        }
    Text(
        text,
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

/**
 * Bose's standby timer, in the vendor app's own words.
 *
 * ⚠ **Zero is "never", not "0 min".** It is a row in Bose Connect's picker rather than a
 * degenerate duration, and printing it as a number would read as "powers off immediately"
 * — the opposite of what it does.
 */
private fun standbyLabel(minutes: Int) =
    when {
        minutes == 0 -> "never"
        minutes >= 60 && minutes % 60 == 0 -> "${minutes / 60} hr"
        else -> "$minutes min"
    }

/**
 * The vendor app's own words for an idle timeout — "30 min", "1 hr", "2 hr".
 *
 * ⚠ Falls back to minutes for anything not a whole number of hours, rather than
 * rounding: [JBL_IDLE_MINUTES] is what this app offers, not what the field can hold,
 * and a value set elsewhere must read back as itself.
 */
private fun idleLabel(minutes: Int) =
    if (minutes >= 60 && minutes % 60 == 0) "${minutes / 60} hr" else "$minutes min"

/**
 * Pick what a control does.
 *
 * ⚠ **[GestureAction.offerable], never [GestureAction.entries].** Three of the actions
 * change the volume and one of them — `0x56` VOLUME_CONTROL — sits nowhere near the other
 * two. Listing them here by hand is the mistake that list was built to prevent.
 *
 * ⚠ **"nothing" is offered on purpose.** Clearing a control is a thing an owner may want,
 * and it is the one write that cannot fail destructively: the refusal case *is* NONE.
 */
@Composable
private fun GesturePicker(
    gesture: Gesture,
    current: GestureAction?,
    onPick: (GestureAction) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(gesture.label) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // ⚠ Says what it COSTS. The vendor app cannot reach this case at all —
                // it only ever offers actions the device accepts — so an owner has no
                // prior experience of a control being declined.
                Text(
                    "These headphones accept a different set for each control, and there " +
                        "is no way to ask which. A refused one is put back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (a in GestureAction.offerable) {
                    Text(
                        if (a == current) "${a.label}  ·  now" else a.label,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(a) }
                                // ⚠ Roomier than the list behind it, on purpose: this is
                                // a target being aimed at, and a mis-tap here writes to
                                // the headphones.
                                .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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

        // Bose Connect's own word for the QC35's weaker cancelling. Not "Ambient":
        // nothing is passed through — see AncMode.ANC_LOW.
        AncMode.ANC_LOW -> "Low"

        AncMode.AMBIENT -> "Ambient"

        AncMode.TALK_THRU -> "TalkThru"
    }

/**
 * Type a new name for the headphones.
 *
 * ⚠ **Seeded with the CURRENT name, not empty.** Renaming is usually editing, and an
 * empty field invites retyping something already correct — which on this device means
 * writing a name the owner did not intend to change.
 *
 * ⚠ **Confirm is disabled for an empty name.** `BoseName.set` refuses one anyway, but a
 * refusal that only happens at the wire shows up as a write that did nothing.
 */
@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("name") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank() && text.trim() != current,
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
