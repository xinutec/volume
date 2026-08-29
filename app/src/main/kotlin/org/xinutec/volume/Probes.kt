package org.xinutec.volume

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import org.xinutec.volume.protocol.AncMode
import org.xinutec.volume.protocol.AutoOff
import org.xinutec.volume.protocol.BoseBands
import org.xinutec.volume.protocol.BoseButton
import org.xinutec.volume.protocol.BoseEq
import org.xinutec.volume.protocol.ButtonWrite
import org.xinutec.volume.protocol.Channels
import org.xinutec.volume.protocol.Confirmation
import org.xinutec.volume.protocol.Drivers
import org.xinutec.volume.protocol.Frames
import org.xinutec.volume.protocol.Hazards
import org.xinutec.volume.protocol.Hex
import org.xinutec.volume.protocol.SonyButton
import org.xinutec.volume.protocol.SonyDsee
import org.xinutec.volume.protocol.SonyEq
import org.xinutec.volume.protocol.SonyFrame
import org.xinutec.volume.protocol.SonyPauseOnRemoval
import org.xinutec.volume.protocol.SonySpeakToChat
import org.xinutec.volume.protocol.SonyTouchPanel
import org.xinutec.volume.protocol.SoundQuality
import org.xinutec.volume.protocol.Sweep
import org.xinutec.volume.protocol.Transport
import org.xinutec.volume.protocol.set
import org.xinutec.volume.protocol.setEq
import org.xinutec.volume.protocol.setMultipoint
import java.util.UUID

/**
 * Everything the #783 probe does, with no opinion about who is asking.
 *
 * ⚠ **Extracted from [MainActivity] so a Service could run it too**, which is the
 * point: an Activity can be told to start and simply not receive the intent —
 * `am start` answers "its current task has been brought to the front", `onNewIntent`
 * never fires, and the run silently does not happen (#967). A started Service has no
 * such state: `onStartCommand` is called for every start, by contract.
 *
 * [emit] is the only thing that differs between callers. [MainActivity] appends to a
 * TextView as well as the log, so a run can be watched while the headphones are on
 * your head; [ProbeService] logs only, which is where `probe.sh` reads from anyway.
 *
 * ⚠ Hearing safety: this sends whatever it is given. Probe with reads. Do not use a
 * volume command as the round-trip proof, and restore any level touched for a test in
 * the same step.
 */
class Probes(
    private val context: Context,
    private val emit: (String) -> Unit,
) {
    fun dispatch(op: String, intent: Intent?) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            emit("no Bluetooth adapter")
            return
        }
        if (!adapter.isEnabled) {
            emit("Bluetooth is OFF — nothing to probe")
            return
        }
        when (op) {
            "list" -> {
                list(adapter)
            }

            "send" -> {
                send(adapter, intent ?: return)
            }

            "sweep" -> {
                sweep(adapter, intent ?: return)
            }

            "seq" -> {
                seq(adapter, intent ?: return)
            }

            "gatt" -> {
                gatt(adapter, intent ?: return)
            }

            "scan" -> {
                scan(adapter, intent)
            }

            "gattmap" -> {
                gattmap(adapter, intent ?: return)
            }

            "anc" -> {
                anc(adapter, intent ?: return)
            }

            "settings" -> {
                settings(adapter, intent ?: return)
            }

            else -> {
                emit(
                    "unknown op '$op' — list, scan, send, sweep, seq, gatt, gattmap, " +
                        "anc, settings",
                )
            }
        }
    }

    /**
     * What the bonded device at [mac] speaks, or null if it is not bonded.
     *
     * ⚠ **A uuid is not a protocol here.** SPP is the QC45's and QC35's control channel
     * AND the JLab JBuds' ([Registry]), so a rule keyed on the uuid would read the
     * JLab's ordinary read as a malformed BMAP frame. [Channels.detect] already knows
     * SPP is ambiguous and answers `NONE` for a device it cannot name.
     *
     * ⚠ Null on any failure, including a missing permission — this feeds a syntax check
     * that must not fire without evidence, and an exception here would otherwise turn a
     * permissions problem into a refused frame.
     */
    private fun protocolOf(
        adapter: android.bluetooth.BluetoothAdapter,
        mac: String,
    ): Channels.Protocol? =
        try {
            adapter.bondedDevices
                ?.firstOrNull { it.address.equals(mac, ignoreCase = true) }
                ?.let { d ->
                    Channels
                        .detect(
                            d.name.orEmpty(),
                            d.uuids
                                ?.map { it.uuid.toString() }
                                .orEmpty()
                                .toSet(),
                        ).protocol
                }
        } catch (e: SecurityException) {
            emit("  (protocol unknown: ${e.message})")
            null
        }

    private fun list(adapter: android.bluetooth.BluetoothAdapter) {
        val bonded =
            try {
                adapter.bondedDevices
            } catch (e: SecurityException) {
                emit("BLUETOOTH_CONNECT not granted: ${e.message}")
                return
            }
        emit("${bonded.size} bonded devices")
        bonded.sortedBy { it.name ?: it.address }.forEach { d ->
            val uuids = d.uuids?.map { it.uuid.toString() }.orEmpty()
            val detected = Channels.detect(d.name.orEmpty(), uuids.toSet())
            emit("")
            emit("${d.name ?: "(unnamed)"}  ${d.address}")
            emit("  detected: $detected")
            uuids.forEach { emit("    $it${Channels.annotate(it)}") }
        }
    }

    private fun send(adapter: android.bluetooth.BluetoothAdapter, intent: Intent) {
        val mac = intent.getStringExtra("mac")
        val uuid = intent.getStringExtra("uuid")
        if (mac == null || uuid == null) {
            emit("send needs --es mac and --es uuid")
            return
        }
        val raw = intent.getBooleanExtra("raw", false)
        val payload = Hex.parse(intent.getStringExtra("payload") ?: "")
        val type = Hex.parse(intent.getStringExtra("type") ?: "0c").first()
        val seq = Hex.parse(intent.getStringExtra("seq") ?: "00").first()
        val totalMs = intent.getIntExtra("wait", 3000).toLong()
        val quietMs = intent.getIntExtra("quiet", 600).toLong()

        // ⚠ **Before the frame is built, and before the socket is opened.** This is the
        // last point where a hand-typed payload is still just bytes on this side. The
        // Sony's `38` means different things per table, so the type byte goes in too.
        //
        // ⚠ **The protocol comes from the DEVICE, not the uuid**, because SPP carries
        // both BMAP and the JLab's own framing — see `Hazards.boseLength`. Resolved
        // here rather than passed in, so a caller that goes straight to the service
        // cannot skip it. Null when nothing is bonded at that address, which keeps the
        // old behaviour instead of guessing.
        val protocol = protocolOf(adapter, mac)
        if (!refused(
                uuid,
                payload,
                table2 = type == SonyFrame.TYPE_DATA_MDR_NO2,
                intent,
                protocol,
            )
        ) {
            return
        }

        val wire = if (raw) payload else SonyFrame.encode(type, seq, payload)

        emit("→ ${if (raw) "raw" else "sony type=%02x seq=%02x".format(type, seq)} to $mac")
        emit("  uuid    $uuid")
        emit("  payload ${Hex.format(payload)}")
        emit("  wire    ${Hex.format(wire)}")

        val device = adapter.getRemoteDevice(mac)
        val r = Probe.exchange(adapter, device, UUID.fromString(uuid), wire, totalMs, quietMs)

        emit("")
        if (r.error != null) {
            emit("✗ no exchange — ${r.error}")
            emit("  if it says 'socket might closed', the vendor app probably holds")
            emit("  the channel: adb shell am force-stop <vendor package>")
            return
        }
        emit("✓ connected (${if (r.secure) "secure" else "insecure"} socket)")
        if (r.received.isEmpty()) {
            emit("← nothing in ${totalMs}ms — channel open, command not answered")
            return
        }
        emit("← ${r.received.size} bytes")
        emit("  ${Hex.format(r.received)}")
        if (!raw) {
            val frames = SonyFrame.decodeAll(r.received)
            if (frames.isEmpty()) {
                // The framing is measured, not assumed (docs/sony-settings.md), so
                // this now means a partial read or a device that is not answering —
                // it is no longer a reason to doubt SonyFrame.
                emit("  (no complete Sony frame — truncated, or nothing was sent)")
            } else {
                frames.forEach { emit("  $it") }
            }
        }
    }

    /**
     * True if this payload may be sent.
     *
     * ⚠ **Refusing is the default and `force` is per-call.** The probe's job is to send
     * whatever it is given, so this cannot be a mode that gets left on: a session that
     * needs `aa 95` types it once, for that one packet, having read why.
     *
     * ⚠ Prints the reason and the bytes together. "Refused" without the payload is not
     * checkable against the docs, and the docs are where the consequence is argued.
     */
    private fun refused(
        uuid: String?,
        payload: ByteArray,
        table2: Boolean,
        intent: Intent,
        protocol: Channels.Protocol? = null,
    ): Boolean {
        // ⚠ **Printed for every send, allowed or refused, and BEFORE the verdict.** The
        // hex is not checkable by a person — `aa 77 03 00 06 05` and `…05 06` differ by a
        // transposition and bind different buttons — so the sentence is what makes a
        // wrong frame visible while it can still be stopped. #1038.
        emit("  means: ${Frames.describe(uuid, payload, table2)}")
        val r = Hazards.check(uuid, payload, table2, protocol) ?: return true
        if (intent.getBooleanExtra("force", false)) {
            emit("⚠ FORCED past a refusal: ${r.what}")
            emit("  ${r.why}")
            return true
        }
        emit("⛔ REFUSED ${Hex.format(payload)}")
        emit("  ${r.what}")
        emit("  ${r.why}")
        emit("  nothing was sent. --ez force true sends it anyway.")
        return false
    }

    /**
     * Every packet of a multi-packet run, checked before ANY of them is sent.
     *
     * ⚠⚠ **FAIL CLOSED ACROSS THE WHOLE RUN.** `seq` exists because some writes are
     * transactional — an operator-`05` Start and then the change — so sending the
     * allowed prefix of a refused run is worse than sending nothing: it leaves the
     * device half-way through a transaction nobody finished. Every packet is judged
     * first, then the run goes or does not.
     *
     * ⚠ **Judged on the payloads, not the wire bytes**, exactly as [send] does: the
     * Sony framing is applied afterwards, and a refusal has to name what was typed.
     */
    private fun anyRefused(
        uuid: String?,
        packets: List<ByteArray>,
        table2: Boolean,
        intent: Intent,
        protocol: Channels.Protocol? = null,
    ): Boolean {
        var bad = false
        packets.forEachIndexed { n, p ->
            emit("  [${n + 1}/${packets.size}]")
            if (!refused(uuid, p, table2, intent, protocol)) bad = true
        }
        if (bad) emit("⛔ nothing was sent — the whole run is refused, not just the packet.")
        return bad
    }

    /**
     * Walk a protocol's read surface and print only what answered.
     *
     * Prints the silent count rather than each silent packet: a sweep is mostly
     * silence, and 300 lines of "nothing" buries the dozen that matter.
     */
    private fun sweep(adapter: android.bluetooth.BluetoothAdapter, intent: Intent) {
        val mac = intent.getStringExtra("mac")
        val uuid = intent.getStringExtra("uuid")
        val proto = intent.getStringExtra("proto")
        if (mac == null || uuid == null || proto == null) {
            emit("sweep needs --es mac, --es uuid and --es proto (bose|fastpair)")
            return
        }
        val blocks = Sweep.range(intent.getStringExtra("blocks") ?: "00-12")
        val fns = Sweep.range(intent.getStringExtra("fns") ?: "00-0f")
        val packets = Sweep.packets(proto, blocks, fns)

        emit("sweep $proto on $mac — ${packets.size} GET packets")
        emit("  blocks ${"%02x".format(blocks.first)}-${"%02x".format(blocks.last)}")
        emit("  fns    ${"%02x".format(fns.first)}-${"%02x".format(fns.last)}")

        var answered = 0
        var silent = 0
        var dropped = 0
        val err =
            Probe.exchangeAll(
                adapter,
                adapter.getRemoteDevice(mac),
                UUID.fromString(uuid),
                packets,
                intent.getIntExtra("per", 400).toLong(),
                intent.getIntExtra("quiet", 150).toLong(),
                intent.getBooleanExtra("reconnect", false),
            ) { sent, got, killedLink ->
                when {
                    killedLink -> {
                        dropped++
                        emit("  ${Hex.format(sent)}  ->  DROPPED THE LINK")
                    }

                    got.isEmpty() -> {
                        silent++
                    }

                    else -> {
                        answered++
                        emit("  ${Hex.format(sent)}  ->  ${Hex.format(got)}")
                    }
                }
            }
        if (err != null) {
            emit("✗ sweep failed — $err")
            return
        }
        emit("done: $answered answered, $silent silent, $dropped killed the link")
    }

    /**
     * Send an explicit list of packets down one socket, in order.
     *
     * This is the write tool. A Bose edit is a transaction — an operator-`05`
     * Start, then the change — so it cannot be expressed one-packet-per-socket,
     * which is what [send] does. Comma-separated hex:
     *
     * ```
     * --es packets 1f010500,1f0602270200...,01050100
     * ```
     */
    private fun seq(adapter: android.bluetooth.BluetoothAdapter, intent: Intent) {
        val mac = intent.getStringExtra("mac")
        val uuid = intent.getStringExtra("uuid")
        val spec = intent.getStringExtra("packets")
        if (mac == null || uuid == null || spec == null) {
            emit("seq needs --es mac, --es uuid and --es packets (comma-separated hex)")
            return
        }
        val raw = spec.split(",").filter { it.isNotBlank() }.map { Hex.parse(it.trim()) }
        // ⚠ Sony needs a SESSION, not a packet. Its PARAM reads answer only inside one
        // where the device's DATA frames are acknowledged; one-shot `send` gets the
        // ACK and nothing else, which reads exactly like "this command returns no
        // data". `--ez sony true` frames each payload and acks what comes back.
        val sony = intent.getBooleanExtra("sony", false)
        // ⚠ Table 2 — see the note on the frame type below. Read-only ops only, for now.
        val table2 = intent.getBooleanExtra("table2", false)
        val sonyType = if (table2) SonyFrame.TYPE_DATA_MDR_NO2 else SonyFrame.TYPE_DATA_MDR
        val packets =
            if (sony) {
                // ⚠ **The frame TYPE selects which command table the bytes mean**, and
                // the two overlap completely in the ranges that matter. `0c` DATA_MDR is
                // table1, `0e` DATA_MDR_NO2 is table2. In table1 `40`–`49` is **VPT**;
                // in table2 the identical bytes are **VOICE_GUIDANCE**. So `48` is a
                // sound-field write on one and a voice-prompt write on the other, and
                // nothing in the payload distinguishes them.
                //
                // ⚠ Default stays `0c`, because that is what every frame this repo has
                // driven used and what the XM4 answers device info on.
                raw.mapIndexed { n, p -> SonyFrame.encode(sonyType, (n % 2).toByte(), p) }
            } else {
                raw
            }
        val acksFor: (ByteArray) -> List<ByteArray> =
            if (!sony) {
                { emptyList() }
            } else {
                { got ->
                    // Acknowledge only what the device SENT as data; acking its acks
                    // would have us talking to ourselves.
                    //
                    // ⚠ **EVERY data frame, not just the last** — the device asks for
                    // one per frame. ⚠ This does NOT fix the one-behind transcript at
                    // the top of #1107: the run below was re-measured with this in
                    // place and still ran one behind. See `Probe.exchangeAll`.
                    SonyFrame
                        .decodeAll(got)
                        .filter { it.type == sonyType }
                        .map { f ->
                            SonyFrame.encode(
                                SonyFrame.TYPE_ACK,
                                (f.seq.toInt() xor 1).toByte(),
                                ByteArray(0),
                            )
                        }
                }
            }
        val how = if (sony) " (sony framed, acked)" else ""
        emit("seq: ${packets.size} packets on one socket to $mac$how")
        // ⚠ **`seq` reached the device with NO hazard check until 2026-08-29.** #1038
        // wired the guard into `send` only, so the repo's most dangerous subcommand —
        // the write tool — was the one without it: `04 07 02 00` CLEAR_DEVICE_LIST went
        // straight out. The shell's `check_frames` is a SHAPE rule and allows it.
        if (anyRefused(uuid, raw, table2, intent, protocolOf(adapter, mac))) return
        var i = 0
        val err =
            Probe.exchangeAll(
                adapter,
                adapter.getRemoteDevice(mac),
                UUID.fromString(uuid),
                packets,
                intent.getIntExtra("per", 900).toLong(),
                intent.getIntExtra("quiet", 350).toLong(),
                intent.getBooleanExtra("reconnect", false),
                acksFor,
            ) { sent, got, _ ->
                i++
                emit("  [$i] → ${Hex.format(sent)}")
                emit("      ← ${if (got.isEmpty()) "(nothing)" else Hex.format(got)}")
                if (sony) {
                    SonyFrame.decodeAll(got).forEach { emit("        $it") }
                }
            }
        if (err != null) emit("✗ seq failed — $err") else emit("done")
    }

    /** What is advertising right now, and under which address. */
    private fun scan(adapter: android.bluetooth.BluetoothAdapter, intent: Intent?) {
        val ms = intent?.getIntExtra("ms", 8000)?.toLong() ?: 8000L
        emit("scanning ${ms}ms…")
        val (seen, err) = Scan.run(adapter, ms)
        if (err != null) emit("⚠ $err")
        emit("${seen.size} advertisers")
        seen.forEach { emit("  $it") }
    }

    /**
     * The real stack, end to end: resolve a bonded device to a driver, open its
     * channel, read the mode, and optionally set one.
     *
     * This is what #785 is built out of — the probe ops above are the instrument
     * that found the bytes, and this is the first thing that uses them the way the
     * app will.
     *
     * ```
     * --es op anc --es device "JBL"            read
     * --es op anc --es device "JBL" --es mode ambient
     * ```
     */
    private fun anc(adapter: android.bluetooth.BluetoothAdapter, intent: Intent) {
        withSession(adapter, intent, "anc") {
            val before = it.headphones.driver.read(it.transport)
            emit("  mode: ${before ?: "(this device has no read command)"}")

            val mode = intent.getStringExtra("mode") ?: return@withSession
            val target =
                AncMode.entries.firstOrNull { m -> m.name.equals(mode, ignoreCase = true) }
            if (target == null) {
                emit("  ✗ '$mode' is not one of ${AncMode.entries}")
                return@withSession
            }
            if (target !in it.headphones.driver.modes) {
                emit(
                    "  ✗ ${it.headphones.model} has no $target, only ${it.headphones.driver.modes}",
                )
                return@withSession
            }
            emit("  → $target")
            report(it.headphones.driver.set(it.transport, target))
        }
    }

    /**
     * Resolve `--es device` to an open session and hand it to [body].
     *
     * Extracted the moment a second op needed it. The connect half is identical for
     * every driver — what differs is only which of the driver's questions get asked,
     * which is [body].
     */
    private fun withSession(
        adapter: android.bluetooth.BluetoothAdapter,
        intent: Intent,
        op: String,
        body: (Session) -> Unit,
    ) {
        val want = intent.getStringExtra("device")
        if (want == null) {
            emit("$op needs --es device (a bonded name substring)")
            return
        }
        val bonded =
            try {
                adapter.bondedDevices.firstOrNull {
                    it.name?.contains(want, ignoreCase = true) == true
                }
            } catch (e: SecurityException) {
                emit("BLUETOOTH_CONNECT not granted: ${e.message}")
                return
            }
        if (bonded == null) {
            emit("no bonded device matching '$want'")
            return
        }
        val uuids =
            bonded.uuids
                ?.map { it.uuid.toString() }
                ?.toSet()
                .orEmpty()
        emit("${bonded.name} ${bonded.address}")

        val session =
            Control.connect(context, adapter, bonded, bonded.name.orEmpty(), uuids, { model ->
                emit("  scanning for $model over LE…")
                Scan.find(adapter, LE_NAMES[model] ?: model, 25000)?.device
            }) { emit("  $it") } ?: return
        session.use {
            emit("  ${it.headphones.model} via ${it.headphones.route}")
            body(it)
        }
    }

    /** One line per outcome, so a write's evidence is never left implicit. */
    private fun report(c: Confirmation<Any>) =
        when (c) {
            is Confirmation.Confirmed -> emit("  ✓ confirmed by read-back")
            is Confirmation.Contradicted -> emit("  ✗ it reads back as ${c.actual}")
            is Confirmation.Unverifiable -> emit("  ⚠ sent; this device cannot confirm it")
        }

    /**
     * Everything decoded that is not ANC: EQ, multipoint, auto-off, the Action button.
     *
     * ⚠ **This op is how those drivers get proven.** They were written on 2026-08-16
     * from a capture and replayed in tests; until something sends them to a headphone
     * they are a hypothesis with good spelling. Read first — with no write argument
     * this only asks questions.
     *
     * ```
     * --es op settings --es device "XM4"                     read everything
     * --es op settings --es device "XM4" --es eq a1          Sony: a preset id, hex
     * --es op settings --es device "XM4" --es autooff never  never | when_removed
     * --es op settings --es device "XM4" --es multipoint on
     * --es op settings --es device "XM4" --es dsee on      DSEE Extreme
     * --es op settings --es device "XM4" --es pause off    pause when removed
     * --es op settings --es device "XM4" --es chat on      Speak-to-Chat
     * --es op settings --es device "XM4" --es voice on     Focus on Voice ⚠ ambient only
     * --es op settings --es device "Bose" --es eq 8,0,0      Bose: bass,mid,treble dB
     * --es op settings --es device "Bose" --es button spotify
     * ```
     */
    private fun settings(adapter: android.bluetooth.BluetoothAdapter, intent: Intent) {
        withSession(adapter, intent, "settings") { session ->
            val t = session.transport
            when (val d = session.headphones.driver) {
                is Drivers.SonyXm4 -> {
                    sonySettings(d, t, intent)
                }

                Drivers.BoseQc45 -> {
                    boseSettings(t, intent)
                }

                else -> {
                    emit("  ${session.headphones.model} has no settings decoded beyond ANC")
                }
            }
        }
    }

    private fun sonySettings(d: Drivers.SonyXm4, t: Transport, intent: Intent) {
        emit("  eq:         ${d.readEq(t) ?: "(no answer)"}")
        emit("  bands:      ${d.bands(t).ifEmpty { "(no answer)" }}")
        emit("  auto-off:   ${d.readAutoOff(t) ?: "(no answer)"}")
        emit("  multipoint: ${d.readMultipoint(t) ?: "(no answer)"}")
        emit("  quality:    ${d.readSoundQuality(t) ?: "(no answer)"}")
        emit("  button:     ${d.readButton(t) ?: "(unexercised code, or no answer)"}")
        emit("  dsee:       ${d.readSwitch(t, SonyDsee) ?: "(no answer)"}")
        emit("  pause:      ${d.readSwitch(t, SonyPauseOnRemoval) ?: "(no answer)"}")
        emit("  chat:       ${d.readSwitch(t, SonySpeakToChat) ?: "(no answer)"}")
        emit("  touch:      ${d.readSwitch(t, SonyTouchPanel) ?: "(no answer)"}")
        emit("  chat detail:${d.readChatDetail(t)?.let { " $it" } ?: " (no answer)"}")
        emit("  voice:      ${d.readFocusOnVoice(t) ?: "(no answer)"}")

        intent.getStringExtra("eq")?.let { arg ->
            val preset = arg.toIntOrNull(16)
            if (preset == null) {
                emit("  ✗ eq wants a preset id in hex, e.g. a1 — not '$arg'")
            } else {
                emit("  → preset ${arg.lowercase()}")
                report(d.setEq(t, preset))
            }
        }
        intent.getStringExtra("eqlevels")?.let { arg ->
            val want = arg.split(",").map { it.trim().toIntOrNull() }
            val levels = want.filterNotNull()
            when {
                levels.size != want.size -> {
                    emit("  ✗ eqlevels wants dB per band, e.g. 3,0,0,2,4,6 — not '$arg'")
                }

                levels.any { it !in SonyEq.RANGE } -> {
                    // The bound is ours, from the app's axis — say so, rather than
                    // letting a refusal here read as the device's answer.
                    emit("  ✗ outside the vendor app's own scale ${SonyEq.RANGE}; not sent")
                }

                else -> {
                    emit("  → levels $levels")
                    report(d.setEqLevels(t, levels))
                }
            }
        }
        intent.getStringExtra("autooff")?.let { arg ->
            val mode = AutoOff.entries.firstOrNull { it.name.equals(arg, ignoreCase = true) }
            if (mode == null) {
                emit("  ✗ '$arg' is not one of ${AutoOff.entries}")
            } else {
                emit("  → $mode")
                // Its own notify echoes the value, but the comparison is still made
                // against a real read — see setMultipoint's note.
                val after = d.writeAutoOff(t, mode) ?: d.readAutoOff(t)
                when (after) {
                    null -> emit("  ⚠ sent; nothing came back to check it against")
                    mode -> emit("  ✓ confirmed")
                    else -> emit("  ✗ it reads back as $after")
                }
            }
        }
        intent.getStringExtra("multipoint")?.let { arg ->
            val on = onOff(arg) ?: return@let emit("  ✗ multipoint wants on|off, not '$arg'")
            emit("  → multipoint $arg")
            report(d.setMultipoint(t, on))
        }
        // The three whose reads were confirmed on 2026-08-23 and whose writes had not
        // been driven when this was written. ⚠ Each is reversible and each is the
        // owner's setting — put back what was there.
        val switches =
            listOf(
                "dsee" to SonyDsee,
                "pause" to SonyPauseOnRemoval,
                "chat" to SonySpeakToChat,
                "touch" to SonyTouchPanel,
            )
        for ((key, switch) in switches) {
            intent.getStringExtra(key)?.let { arg ->
                val on = onOff(arg) ?: return@let emit("  ✗ $key wants on|off, not '$arg'")
                emit("  → $key $arg")
                report(d.setSwitch(t, switch, on))
            }
        }
        // ⚠ Ambient mode only — see setFocusOnVoice. In ANC this reports the value that
        // is really there rather than sending a frame the device will drop.
        intent.getStringExtra("voice")?.let { arg ->
            val on = onOff(arg) ?: return@let emit("  ✗ voice wants on|off, not '$arg'")
            emit("  → focus on voice $arg")
            report(d.setFocusOnVoice(t, on))
        }
        intent.getStringExtra("quality")?.let { arg ->
            val mode = SoundQuality.entries.firstOrNull { it.name.equals(arg, ignoreCase = true) }
            if (mode == null) {
                emit("  ✗ '$arg' is not one of ${SoundQuality.entries}")
            } else {
                emit("  → $mode (this renegotiates the codec; the link drops and returns)")
                val after = d.writeSoundQuality(t, mode) ?: d.readSoundQuality(t)
                when (after) {
                    null -> emit("  ⚠ sent; nothing came back to check it against")
                    mode -> emit("  ✓ confirmed")
                    else -> emit("  ✗ it reads back as $after")
                }
            }
        }
        intent.getStringExtra("button")?.let { arg ->
            val action =
                SonyButton.Action.entries.firstOrNull { it.name.equals(arg, ignoreCase = true) }
            if (action == null) {
                emit("  ✗ '$arg' is not one of ${SonyButton.Action.entries}")
                return@let
            }
            // ⚠ **This asks the DEVICE, which then asks the OWNER.** The probe answers
            // for them, which is only defensible here: a terminal command is somebody
            // deliberately driving their own headphones. The card must not — see #965.
            emit("  → $action (subscribing to alerts first)")
            when (d.beginButtonWrite(t, action)) {
                ButtonWrite.Asks -> {
                    emit("  ← the device asks about disconnecting; answering yes")
                    // ⚠ This kills the link on purpose. The read below reopens.
                    d.answerButtonAlert(t, true)
                }

                ButtonWrite.Unchanged -> {
                    emit("  ← no alert — nothing changed")
                }
            }
            val after = runCatching { d.readButton(t) }.getOrNull()
            when (after) {
                null -> emit("  ⚠ link went with the commit — re-run `settings` to see it")
                action -> emit("  ✓ confirmed")
                else -> emit("  ✗ it reads back as $after")
            }
        }
    }

    private fun boseSettings(t: Transport, intent: Intent) {
        val d = Drivers.BoseQc45
        emit("  eq:         ${d.readEq(t) ?: "(no answer)"}")
        emit("  multipoint: ${d.readMultipoint(t) ?: "(no answer)"}")
        emit("  button:     ${d.readButton(t) ?: "(unexercised code, or no answer)"}")

        intent.getStringExtra("eq")?.let { arg ->
            val n = arg.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (n.size != 3) {
                emit("  ✗ eq wants bass,mid,treble in dB, e.g. 8,0,0 — not '$arg'")
                return@let
            }
            val want = BoseBands(bass = n[0], mid = n[1], treble = n[2])
            if (n.any { it !in BoseEq.RANGE }) {
                // ⚠ The range itself is inferred, so refusing here is a guess about a
                // guess. Say so rather than pretending the device rejected it.
                emit("  ✗ $want is outside the INFERRED range ${BoseEq.RANGE}; not sent")
                return@let
            }
            emit("  → $want")
            val after = d.writeEq(t, want) ?: d.readEq(t)
            when (after) {
                null -> emit("  ⚠ sent; nothing came back to check it against")
                want -> emit("  ✓ confirmed")
                else -> emit("  ✗ it reads back as $after")
            }
        }
        intent.getStringExtra("multipoint")?.let { arg ->
            val on = onOff(arg) ?: return@let emit("  ✗ multipoint wants on|off, not '$arg'")
            emit("  → multipoint $arg")
            report(d.setMultipoint(t, on))
        }
        intent.getStringExtra("button")?.let { arg ->
            val action =
                BoseButton.Action.entries.firstOrNull { it.name.equals(arg, ignoreCase = true) }
            if (action == null) {
                emit("  ✗ '$arg' is not one of ${BoseButton.Action.entries}")
                return@let
            }
            emit("  → $action")
            val after = d.writeButton(t, action) ?: d.readButton(t)
            when (after) {
                null -> emit("  ⚠ sent; nothing came back to check it against")
                action -> emit("  ✓ confirmed")
                else -> emit("  ✗ it reads back as $after")
            }
        }
    }

    private fun onOff(arg: String): Boolean? =
        when (arg.lowercase()) {
            "on", "true", "1" -> true
            "off", "false", "0" -> false
            else -> null
        }

    /** Every GATT service and characteristic a device offers, with its properties. */
    private fun gattmap(adapter: android.bluetooth.BluetoothAdapter, intent: Intent) {
        val name =
            intent.getStringExtra("name") ?: run {
                emit("gattmap needs --es name")
                return
            }
        emit("resolving '$name' by LE scan…")
        val seen = Scan.find(adapter, name, intent.getIntExtra("scan", 25000).toLong())
        if (seen?.device == null) {
            emit("✗ nothing advertising anything containing '$name'")
            return
        }
        emit("  $seen")
        val (lines, err) =
            Gatt.map(
                context,
                seen.device,
                intent.getIntExtra("connect", 20000).toLong(),
            )
        lines.forEach { emit("  $it") }
        if (err != null) emit("✗ $err")
    }

    /**
     * The GATT counterpart of [seq]: one LE connection, several writes, in order.
     *
     * Defaults to the BES command service, since that is the one channel found this
     * way so far; `--es service/write/notify` override it for the next device.
     *
     * ```
     * --es name "JBL TOUR" --es packets aa910111
     * ```
     *
     * ⚠ Prefer `--es name` over `--es mac`. The address these devices advertise
     * rotates, so a literal one goes stale — and connecting to a stale address fails
     * slowly and misleadingly rather than saying "no such device".
     */
    private fun gatt(adapter: android.bluetooth.BluetoothAdapter, intent: Intent) {
        val spec = intent.getStringExtra("packets")
        val name = intent.getStringExtra("name")
        val mac = intent.getStringExtra("mac")
        if (spec == null || (name == null && mac == null)) {
            emit("gatt needs --es packets, and --es name (preferred) or --es mac")
            return
        }
        val device =
            if (name != null) {
                emit("resolving '$name' by LE scan…")
                // 25 s, not 10: these devices advertise in bursts and rotate address
                // between them, and a window that misses one reports "not advertising"
                // — indistinguishable from "not there". The scan stops on the first
                // match, so a generous window costs nothing when the device is present.
                val seen = Scan.find(adapter, name, intent.getIntExtra("scan", 25000).toLong())
                if (seen?.device == null) {
                    emit("✗ nothing advertising anything containing '$name'")
                    return
                }
                emit("  $seen")
                seen.device
            } else {
                adapter.getRemoteDevice(mac)
            }
        val service = intent.getStringExtra("service") ?: Channels.BES_GATT_SERVICE
        val write = intent.getStringExtra("write") ?: Channels.BES_GATT_WRITE
        val notify = intent.getStringExtra("notify") ?: Channels.BES_GATT_NOTIFY
        val packets = spec.split(",").filter { it.isNotBlank() }.map { Hex.parse(it.trim()) }

        emit("gatt: ${packets.size} writes to ${device.address}")
        emit("  service $service")
        // ⚠⚠ **THIS is the path `aa 95` is typed on.** The JBL is driven over GATT, the
        // BES factory reset lives there, and this subcommand had no hazard check at all
        // until 2026-08-29 — the one place the repo's loudest standing rule applies.
        // A null uuid falls through to the BES rules, which is what catches it.
        if (anyRefused(null, packets, table2 = false, intent = intent)) return
        var i = 0
        val err =
            Gatt.exchange(
                context,
                device,
                UUID.fromString(service),
                UUID.fromString(write),
                UUID.fromString(notify),
                packets,
                intent.getIntExtra("per", 1500).toLong(),
                intent.getIntExtra("quiet", 500).toLong(),
                // Direct by default — see Gatt.exchange: an accept-list connect cannot
                // catch a rotating address. `--ez auto true` for a device whose address
                // is fixed and which may not be advertising yet.
                intent.getBooleanExtra("auto", false),
                intent.getIntExtra("connect", 20000).toLong(),
            ) { r ->
                i++
                emit("  [$i] → ${Hex.format(r.sent)}")
                emit("      ← ${if (r.received.isEmpty()) "(nothing)" else Hex.format(r.received)}")
                r.error?.let { emit("      ⚠ $it") }
            }
        if (err != null) emit("✗ gatt failed — $err") else emit("done")
    }

    companion object {
        /**
         * What a device calls itself over LE, when that differs from its bonded
         * name. ⚠ The JLab advertises no name at all, so it is matched on a stable
         * run inside its Fast Pair service data instead.
         */
        private val LE_NAMES =
            mapOf(
                "JBL Tour One M2" to "JBL TOUR",
                "JLab JBuds Sport ANC 4" to "21 55 35 33",
            )
    }
}
