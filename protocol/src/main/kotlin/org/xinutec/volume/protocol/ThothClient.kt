package org.xinutec.volume.protocol

import org.json.JSONArray
import org.json.JSONObject

/** One HTTP exchange with the Mac, reduced to what any decision here needs. */
data class ThothReply(
    val status: Int,
    val body: String,
)

/**
 * The socket, which is `:app`'s to supply.
 *
 * Same seam as the Bluetooth transports: the interface is here so that every request
 * this app can make, and every reply it can be given, is exercised on a JVM with no
 * network at all. A fake that returns captured bodies is the whole test rig.
 */
interface ThothTransport {
    /**
     * @param body the request entity for a POST, or null for a GET.
     * @throws java.io.IOException when the host could not be reached at all — which
     *   is a different thing from being reached and told no. See [ThothRefused].
     */
    fun send(method: String, path: String, body: String?): ThothReply
}

/**
 * The Mac answered, and the answer was no.
 *
 * ⚠ [reason] is the server's own words and is meant to be SHOWN. The refusal that
 * matters is the volume ceiling, whose whole design is that the caller finds out what
 * refused them and why — swallowing this would restore exactly the silence it exists
 * to prevent.
 */
class ThothRefused(
    val status: Int,
    val reason: String,
) : RuntimeException(reason)

/** A change to the pair. Every field optional; the nulls are "leave it alone". */
data class PairPatch(
    val left: String? = null,
    val right: String? = null,
    val stereo: Boolean? = null,
    val balance: Double? = null,
    val volume: Double? = null,
) {
    val empty: Boolean
        get() = left == null && right == null && stereo == null && balance == null && volume == null

    /**
     * Fold a later change into this one, later wins per field.
     *
     * A drag produces a change per frame and they must not become a request per
     * frame. Merging keeps the LAST value of each field, so the level the thumb
     * lands on is the one that goes, and a balance nudge mid-drag is not lost to a
     * volume move that happened to arrive after it.
     */
    fun and(next: PairPatch) =
        PairPatch(
            left = next.left ?: left,
            right = next.right ?: right,
            stereo = next.stereo ?: stereo,
            balance = next.balance ?: balance,
            volume = next.volume ?: volume,
        )
}

/**
 * Every byte that goes to the Mac and comes back.
 *
 * ⚠ **A reply that does not parse throws.** No field here is defaulted into
 * existence: a missing `volume` would become a level to draw, and a slider seeded
 * from a value the server never sent is a slider that moves the speakers to it on
 * first touch. The controller turns the throw into [ThothReach.AWAY], which is the
 * true statement.
 *
 * The one deliberately absent field is [ThothPair.ceiling] — see there.
 */
object ThothWire {
    fun pair(json: String): ThothPair {
        val o = JSONObject(json)
        return ThothPair(
            left = o.optString("left", ""),
            right = o.optString("right", ""),
            stereo = o.getBoolean("stereo"),
            balance = o.getDouble("balance"),
            volume = o.getDouble("volume"),
            active = o.getBoolean("active"),
            ceiling = o.optDouble("ceiling").takeIf { !it.isNaN() },
        )
    }

    fun devices(json: String): List<ThothDevice> =
        each(JSONObject(json).getJSONArray("devices")) {
            ThothDevice(
                name = it.getString("name"),
                uid = it.getString("uid"),
                aggregate = it.getBoolean("aggregate"),
            )
        }

    fun input(json: String): ThothInput {
        val o = JSONObject(json)
        return ThothInput(
            devices =
                each(o.getJSONArray("devices")) {
                    ThothInputDevice(name = it.getString("name"), uid = it.getString("uid"))
                },
            current = o.optString("current", ""),
            pinned = o.optString("pinned", ""),
        )
    }

    fun cabinets(json: String): List<ThothCabinet> =
        each(JSONObject(json).getJSONArray("cabinets")) { cabinet(it) }

    fun cabinet(json: String): ThothCabinet = cabinet(JSONObject(json))

    /**
     * ⚠ `raw` and `steps` are read through [has] rather than `optInt`, whose absent
     * value is 0 — which is also a real reading. A cabinet sitting at silence and a
     * cabinet that reported no level are not the same cabinet.
     */
    private fun cabinet(o: JSONObject) =
        ThothCabinet(
            host = o.getString("host"),
            status = CabinetStatus.of(o.getString("status")),
            volume = if (o.has("volume")) o.getDouble("volume") else null,
            raw = if (o.has("raw")) o.getInt("raw") else null,
            steps = if (o.has("ceiling")) o.getInt("ceiling") else null,
        )

    /** The body of a pair change. Absent fields are absent, not null-valued. */
    fun body(patch: PairPatch): String {
        val o = JSONObject()
        patch.left?.let { o.put("left", it) }
        patch.right?.let { o.put("right", it) }
        patch.stereo?.let { o.put("stereo", it) }
        patch.balance?.let { o.put("balance", it) }
        patch.volume?.let { o.put("volume", it) }
        return o.toString()
    }

    /** The body of an input pick, or of a pin. `""` clears a pin. */
    fun uid(uid: String): String = JSONObject().put("uid", uid).toString()

    fun picade(host: String, volume: Double): String =
        JSONObject().put("host", host).put("volume", volume).toString()

    private fun <T> each(a: JSONArray, one: (JSONObject) -> T): List<T> =
        (0 until a.length()).map { one(a.getJSONObject(it)) }
}

/**
 * The Mac's audio, as calls.
 *
 * Thin on purpose: it turns a path and a body into a parsed reply and a non-2xx into
 * [ThothRefused]. Everything that decides — what a reading means, how far a control
 * may travel — is in `Thoth.kt`, and everything that blocks is the caller's.
 */
class ThothClient(
    private val io: ThothTransport,
) {
    fun pair(): ThothPair = ThothWire.pair(get("/api/pair"))

    fun devices(): List<ThothDevice> = ThothWire.devices(get("/api/devices"))

    fun input(): ThothInput = ThothWire.input(get("/api/input"))

    fun cabinets(): List<ThothCabinet> = ThothWire.cabinets(get("/api/picades"))

    fun setPair(patch: PairPatch): ThothPair =
        ThothWire.pair(post("/api/pair", ThothWire.body(patch)))

    fun recalibrate(): ThothPair = ThothWire.pair(post("/api/pair/recalibrate", "{}"))

    /** Switch the default input now, leaving any standing pin alone. */
    fun setInput(uid: String): ThothInput = ThothWire.input(post("/api/input", ThothWire.uid(uid)))

    /** Move the pin, or clear it with `""`. */
    fun setInputPin(uid: String): ThothInput =
        ThothWire.input(post("/api/input/pin", ThothWire.uid(uid)))

    fun setCabinet(host: String, volume: Double): ThothCabinet =
        ThothWire.cabinet(post("/api/picades", ThothWire.picade(host, volume)))

    private fun get(path: String) = ok(io.send("GET", path, null))

    private fun post(path: String, body: String) = ok(io.send("POST", path, body))

    private fun ok(r: ThothReply): String {
        if (r.status !in 200..299) {
            throw ThothRefused(r.status, r.body.trim().ifEmpty { "HTTP ${r.status}" })
        }
        return r.body
    }
}

/**
 * A typed host into an origin to prefix a path with.
 *
 * ⚠ Written for a text field, so it takes what somebody would actually type. A
 * pasted `http://host:8089/` and a bare `host` have to reach the same place, and the
 * port is defaulted because leaving it off is the likeliest way to type this wrong —
 * the service has never been on 80.
 */
fun thothOrigin(host: String): String {
    var h =
        host
            .trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
    if (h.isEmpty()) h = THOTH_DEFAULT_HOST
    if (!h.contains(':')) h = "$h:$THOTH_PORT"
    return "http://$h"
}
