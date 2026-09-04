package org.xinutec.volume

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.xinutec.volume.protocol.ThothCabinet
import org.xinutec.volume.protocol.ThothReach
import org.xinutec.volume.protocol.ThothScreen
import org.xinutec.volume.protocol.balancePercent
import org.xinutec.volume.protocol.note
import org.xinutec.volume.protocol.percent
import org.xinutec.volume.protocol.reachable
import org.xinutec.volume.protocol.volumeControl
import org.xinutec.volume.protocol.volumePercent
import kotlin.math.roundToInt

/** What the Mac card can ask for. Implemented by [VolumeActivity] over the controller. */
interface ThothActions {
    fun setLeft(uid: String)

    fun setRight(uid: String)

    fun setStereo(on: Boolean)

    fun setVolume(percent: Int)

    fun setBalance(coarse: Int, fine: Int)

    fun recalibrate()

    fun chooseInput(uid: String)

    fun pinInput(on: Boolean)

    fun setCabinet(cabinet: String, percent: Int)

    fun setHost(host: String)
}

/**
 * What the Mac card is SHOWING, which is not the same as what the Mac last said.
 *
 * ⚠ **A control has to answer the thumb, and the server cannot.** Every reading thoth
 * returns is derived from the hardware at the moment it is asked, so the confirmation
 * of a drag is a round-trip away — bind a slider straight to it and the thumb springs
 * back to the last poll on every frame. So the displayed value is held here, and the
 * server's is adopted only when a poll brings a DIFFERENT one, which by construction
 * happens only after the drag has settled (the controller does not poll while an edit
 * is pending, in flight, or 2.5 s old).
 *
 * This is also what the hardware volume keys move: they and the slider are the same
 * control, and two copies of "where the volume is" would disagree the first time
 * somebody used both.
 */
class ThothUi(
    host: String,
) {
    var screen by mutableStateOf(ThothScreen.looking(host))
        private set

    private var volume by mutableStateOf<Int?>(null)
    private var coarse by mutableStateOf<Int?>(null)
    private var fine by mutableStateOf(0)
    private val levels = mutableStateMapOf<String, Int>()

    /**
     * Take a snapshot from the controller.
     *
     * ⚠ The local values are dropped only when the server's have actually MOVED. A
     * poll that returns an equal state — the common case, three times every ten
     * seconds — leaves a half-finished adjustment alone.
     */
    fun adopt(next: ThothScreen) {
        if (next.pair != screen.pair) {
            volume = null
            coarse = null
            fine = 0
        }
        if (next.cabinets != screen.cabinets) levels.clear()
        screen = next
    }

    val shownVolume: Int
        get() = volume ?: screen.pair?.volumePercent ?: 0

    val shownCoarse: Int
        get() = coarse ?: screen.pair?.balancePercent ?: 0

    val shownFine: Int
        get() = fine

    fun shown(cabinet: ThothCabinet): Int = levels[cabinet.host] ?: cabinet.percent

    fun onVolume(percent: Int, actions: ThothActions) {
        volume = percent
        actions.setVolume(percent)
    }

    fun onBalance(coarse: Int, fine: Int, actions: ThothActions) {
        this.coarse = coarse
        this.fine = fine
        actions.setBalance(coarse, fine)
    }

    fun onCabinet(cabinet: ThothCabinet, percent: Int, actions: ThothActions) {
        levels[cabinet.host] = percent
        actions.setCabinet(cabinet.host, percent)
    }

    /**
     * There is a pair answering, so the hardware volume keys belong to it.
     *
     * ⚠ Read by the key-up handler as well as by [step], and both have to agree: a
     * press consumed whose release is not leaves the system showing its own volume
     * panel over the app.
     */
    val drivesVolumeKeys: Boolean
        get() = screen.reach == ThothReach.LIVE && screen.pair != null

    /**
     * A hardware volume key, ±2%.
     *
     * ⚠ Returns false when there is nothing to drive, and the key must then be left
     * to the system — swallowing it would take the phone's own volume keys away for
     * as long as this app is in front, which is what happens off the home network.
     *
     * Key auto-repeat plus the controller's 80 ms coalescing give hold-to-ramp for
     * free: the presses become one request per 80 ms, not one request per press.
     */
    fun step(direction: Int, actions: ThothActions): Boolean {
        if (!drivesVolumeKeys) return false
        val bound = screen.pair!!.volumeControl()
        val next = (shownVolume + 2 * direction).coerceIn(0, bound.maxPercent)
        if (next != shownVolume) onVolume(next, actions)
        // Consumed either way: at the bound the press must not fall through and move
        // the phone's media volume instead, which looks like the bound not working.
        return true
    }
}

/**
 * The Mac's audio: the stereo pair, the microphone pin, and the arcade cabinets.
 *
 * ⚠ **First in the list, above the headphones.** It is the control that is wanted
 * while sitting in the room the speakers are in, and it is one card; the headphones
 * below it are the ones that travel. When the Mac is not reachable this collapses to
 * a single line, so off the home network it costs a row rather than a screenful.
 *
 * Everything it decides is decided in `:protocol` — see [ThothScreen].
 */
@Composable
fun ThothCard(ui: ThothUi, actions: ThothActions) {
    val screen = ui.screen
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Audio Home", style = MaterialTheme.typography.titleMedium)
                val pair = screen.pair
                if (pair != null) {
                    Text(
                        if (pair.active) "pair active" else "pair not set",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val trouble = screen.trouble
            if (trouble != null) {
                Trouble(screen, trouble, actions)
                return@Column
            }
            PairControls(ui, actions)
            MicControls(ui, actions)
            Cabinets(ui, actions)
            screen.refusal?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * The card with nothing in it, plus the one thing worth offering there.
 *
 * ⚠ The address field appears HERE and nowhere else. A LAN address is not a setting
 * anybody wants to look at; it is the answer to exactly one question, and this is the
 * only screen on which that question has been asked.
 */
@Composable
private fun Trouble(screen: ThothScreen, trouble: String, actions: ThothActions) {
    Text(trouble, style = MaterialTheme.typography.bodyMedium)
    if (screen.reach != ThothReach.AWAY) return
    var typed by remember(screen.host) { mutableStateOf(screen.host) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("Address") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { actions.setHost(typed) }) { Text("Look") }
    }
}

@Composable
private fun PairControls(ui: ThothUi, actions: ThothActions) {
    val screen = ui.screen
    val pair = screen.pair ?: return
    var picking by remember { mutableStateOf<Side?>(null) }
    val name = { uid: String -> screen.outputs.firstOrNull { it.uid == uid }?.name ?: "—" }

    Picked("Left", name(pair.left)) { picking = Side.LEFT }
    Picked("Right", name(pair.right)) { picking = Side.RIGHT }
    Toggle(
        title = if (pair.stereo) "Stereo" else "Mono",
        value = if (pair.stereo) "split left / right" else "both play everything",
        checked = pair.stereo,
        onChange = actions::setStereo,
    )

    val bound = pair.volumeControl()
    Level(
        label = "Volume",
        percent = ui.shownVolume,
        max = bound.maxPercent,
        unit = "%",
        note = bound.why,
        emphasis = bound.over,
    ) { ui.onVolume(it, actions) }

    Text(
        "Balance",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 4.dp),
    )
    // Two controls because one cannot do both jobs: the useful range is a few percent
    // either side of centre, and a single ±100 slider cannot be nudged that finely.
    Level("Coarse", ui.shownCoarse, max = 100, min = -100) {
        ui.onBalance(it, ui.shownFine, actions)
    }
    Level("Fine", ui.shownFine, max = 20, min = -20) {
        ui.onBalance(ui.shownCoarse, it, actions)
    }
    TextButton(onClick = actions::recalibrate) { Text("Re-sync the two clocks") }

    picking?.let { side ->
        ThothPicker(
            title = if (side == Side.LEFT) "Left speaker" else "Right speaker",
            options = screen.outputs.map { it.uid to it.name },
            current = if (side == Side.LEFT) pair.left else pair.right,
            onPick = {
                if (side == Side.LEFT) actions.setLeft(it) else actions.setRight(it)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

private enum class Side { LEFT, RIGHT }

@Composable
private fun MicControls(ui: ThothUi, actions: ThothActions) {
    val input = ui.screen.input ?: return
    var picking by remember { mutableStateOf(false) }
    val name = input.devices.firstOrNull { it.uid == input.current }?.name ?: "—"
    Picked("Mic", name) { picking = true }
    Toggle(
        title = "Pinned",
        // ⚠ Says what the pin DOES, because the failure it prevents is invisible:
        // macOS re-points the default input at whatever connects, and a Bluetooth
        // speaker's hands-free mic taking it sounds like a broken microphone.
        value =
            if (input.pinned.isEmpty()) {
                "macOS picks the default input"
            } else {
                "put back whenever macOS switches it"
            },
        checked = input.pinned.isNotEmpty(),
        onChange = actions::pinInput,
    )
    if (picking) {
        ThothPicker(
            title = "Default input",
            options = input.devices.map { it.uid to it.name },
            current = input.current,
            onPick = {
                actions.chooseInput(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun Cabinets(ui: ThothUi, actions: ThothActions) {
    val cabinets = ui.screen.cabinets
    if (cabinets.isEmpty()) return
    Text(
        "Arcade cabinets",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 4.dp),
    )
    for (cabinet in cabinets) {
        if (cabinet.reachable) {
            val bound = ui.screen.boundFor(cabinet)
            Level(
                label = cabinet.host,
                percent = ui.shown(cabinet),
                max = bound.maxPercent,
                unit = "%",
                note = ui.screen.noteFor(cabinet),
                emphasis = bound.notable,
            ) { ui.onCabinet(cabinet, it, actions) }
        } else {
            // ⚠ Listed, not omitted. The row is the fleet; a cabinet that is off is a
            // fact about that cabinet, and dropping it would read as one we do not know
            // about at all.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(cabinet.host, style = MaterialTheme.typography.bodyMedium)
                Text(
                    cabinet.note.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A label, its current choice, and a tap that opens the list. */
@Composable
private fun Picked(title: String, value: String, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun Toggle(title: String, value: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ⚠ `weight(1f)`, for the reason SettingRow gives: without it the label takes
        // its intrinsic width and pushes the switch off a narrow screen.
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * One slider, with the number beside it and the reason it stops where it stops.
 *
 * ⚠ [note] is drawn, always. On the volume control it is the sentence naming the
 * hearing ceiling, and a bound whose reason is not on the screen is a control that
 * looks broken.
 */
@Composable
private fun Level(
    label: String,
    percent: Int,
    max: Int,
    min: Int = 0,
    /**
     * ⚠ Empty for balance, which is NOT a percentage — it is a position between two
     * speakers, and "0%" would read as silence rather than as centre.
     */
    unit: String = "",
    note: String? = null,
    emphasis: Boolean = false,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(0.3f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // ⚠ A Slider whose range is empty is not a slider. `max` is a BOUND,
            // and the bound collapses to 0 for real states — a pair sitting at
            // silence on a server that publishes no ceiling can only come down,
            // and down from 0 is nowhere. Drawing the number without a track says
            // that; a `0f..0f` range would be a division by the range's width.
            if (max > min) {
                Slider(
                    value = percent.toFloat(),
                    onValueChange = { onChange(it.roundToInt()) },
                    valueRange = min.toFloat()..max.toFloat(),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                "$percent$unit",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(0.22f),
            )
        }
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (emphasis) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

/** The app's picker shape: a list of choices, the current one marked. */
@Composable
private fun ThothPicker(
    title: String,
    options: List<Pair<String, String>>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (options.isEmpty()) {
                    Text("The Mac listed none.")
                }
                for ((uid, name) in options) {
                    Text(
                        if (uid == current) "$name  ·  now" else name,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(uid) }
                                .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
