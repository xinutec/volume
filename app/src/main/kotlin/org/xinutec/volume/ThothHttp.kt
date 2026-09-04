package org.xinutec.volume

import org.xinutec.volume.protocol.ThothReply
import org.xinutec.volume.protocol.ThothTransport
import org.xinutec.volume.protocol.thothOrigin
import java.net.HttpURLConnection
import java.net.URL

/**
 * The socket the thoth contract runs over.
 *
 * `HttpURLConnection` and nothing else: this app talks to one appliance on the LAN
 * over four endpoints, and a client library would be a dependency carried for the
 * request-building that `:protocol` already does.
 *
 * ⚠ **Cleartext, deliberately.** thoth has no TLS and no auth — it is a control panel
 * for the speakers in one room, on one LAN, and the threat model there is destruction
 * rather than observation. The manifest permits cleartext for that reason and this
 * traffic is the only reason it does.
 *
 * @param host looked up per request, not captured, so an address changed in the card
 *   takes effect on the next poll rather than on the next launch.
 */
class ThothHttp(
    private val host: () -> String,
) : ThothTransport {
    override fun send(method: String, path: String, body: String?): ThothReply {
        val c = URL(thothOrigin(host()) + path).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = method
            // ⚠ Short, and the connect timeout is the one that matters. Off this
            // network the address is usually unroutable rather than refused, so
            // without a bound the poll would stack up one stalled socket every 3 s.
            c.connectTimeout = CONNECT_MS
            // Longer than the connect: `/api/picades` may be probing five cabinets
            // over SSH, and the ones that are off are what make it slow.
            c.readTimeout = READ_MS
            c.useCaches = false
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = c.responseCode
            // ⚠ A non-2xx body arrives on the ERROR stream; `inputStream` throws for
            // it. That body is the server's refusal text — the thing most worth
            // showing — so reading the wrong stream would turn a sentence explaining
            // the volume ceiling into a bare status code.
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            ThothReply(status, text)
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val CONNECT_MS = 1_500
        const val READ_MS = 6_000
    }
}
