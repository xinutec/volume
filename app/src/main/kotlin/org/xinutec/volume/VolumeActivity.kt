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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import org.xinutec.volume.protocol.AncMode
import org.xinutec.volume.protocol.DeviceCard
import org.xinutec.volume.protocol.DeviceState
import org.xinutec.volume.protocol.Emptiness
import org.xinutec.volume.protocol.NoteKind
import org.xinutec.volume.protocol.Screen

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

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(links) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VolumeScreen(screen: Screen, onConnect: (String) -> Unit, onSet: (String, AncMode) -> Unit) {
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
                DeviceRow(card, onConnect, onSet)
            }
        }
    }
}

@Composable
private fun DeviceRow(
    card: DeviceCard,
    onConnect: (String) -> Unit,
    onSet: (String, AncMode) -> Unit,
) {
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
        }
    }
}

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
