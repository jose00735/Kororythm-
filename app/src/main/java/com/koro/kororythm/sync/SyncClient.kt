package com.koro.kororythm.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class SyncException(message: String, val status: Int = 0) : IOException(message)

/**
 * Talks HTTPS to one PC. The PC's certificate is self-signed, so instead of a CA the
 * connection is pinned to its SHA-256 [fp] (learned at pairing, where the code proof binds it).
 */
class SyncClient(val host: String, val port: Int, private val pin: String?, private val token: String? = null) {

    /** The certificate fingerprint the server actually showed (set after the first request). */
    @Volatile var seenFp: String? = null
        private set

    private val trust = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw java.security.cert.CertificateException()
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val fp = Fingerprint.hex(MessageDigest.getInstance("SHA-256").digest(chain[0].encoded))
            seenFp = fp
            if (pin != null && !fp.equals(pin, ignoreCase = true)) throw java.security.cert.CertificateException("This isn't the PC you paired with (its key changed)")
        }
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
    }
    private val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }

    private fun open(path: String, method: String = "GET"): HttpsURLConnection {
        val h = if (host.contains(':')) "[$host]" else host
        val c = URL("https://$h:$port$path").openConnection() as HttpsURLConnection
        c.sslSocketFactory = ssl.socketFactory
        c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true } // pinned above instead
        c.requestMethod = method
        c.connectTimeout = 5_000
        c.readTimeout = 60_000
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        return c
    }

    private fun check(c: HttpURLConnection): HttpURLConnection {
        val code = c.responseCode
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(c.errorStream.bufferedReader().readText()).optString("error") }.getOrNull()
            throw SyncException(msg?.takeIf { it.isNotBlank() } ?: "PC answered $code", code)
        }
        return c
    }

    private fun json(c: HttpURLConnection): JSONObject = check(c).inputStream.bufferedReader().use { JSONObject(it.readText()) }

    private fun post(path: String, body: JSONObject): JSONObject {
        val c = open(path, "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        return json(c)
    }

    suspend fun hello(): PcHello = withContext(Dispatchers.IO) { PcHello.parse(json(open("/v1/hello")), host) }

    /** Pairs with the code shown on the PC; returns the token, after checking the PC's own proof. */
    suspend fun pair(code: String, device: String, name: String): Pair<String, PcHello> = withContext(Dispatchers.IO) {
        val hello = PcHello.parse(json(open("/v1/hello")), host)
        val fp = seenFp ?: throw SyncException("No certificate from the PC")
        val nonce = Fingerprint.hex(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val r = post("/v1/pair", JSONObject().put("device", device).put("name", name).put("nonce", nonce)
            .put("proof", SyncProtocol.proof(code, "kororythm-pair", nonce, fp)))
        if (seenFp != fp) throw SyncException("The PC's key changed while pairing")
        if (r.optString("proof") != SyncProtocol.proof(code, "kororythm-server", nonce, fp)) throw SyncException("The PC couldn't prove it showed that code")
        r.getString("token") to hello.copy(fp = fp)
    }

    suspend fun start(device: String, tracks: List<ManifestTrack>, playlists: List<ManifestPlaylist>, options: SyncOptions): Plan =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("device", device)
                .put("tracks", org.json.JSONArray(tracks.map { it.json() }))
                .put("playlists", org.json.JSONArray(playlists.map { it.json() }))
                .put("options", options.json())
            Plan.parse(post("/v1/sync/start", body))
        }

    /** Downloads a song into [dest], resuming a partial file; [progress] gets bytes as they come. */
    suspend fun download(session: String, fp: String, dest: File, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        val have = if (dest.exists()) dest.length() else 0L
        val c = open("/v1/files/$fp?s=$session")
        c.readTimeout = 120_000
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        check(c)
        val append = have > 0 && c.responseCode == 206
        java.io.FileOutputStream(dest, append).use { out -> copy(c.inputStream, out, progress) }
    }

    /** Uploads a song, resuming where the PC's copy stopped. */
    suspend fun upload(session: String, fp: String, size: Long, source: () -> InputStream, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        val offset = json(open("/v1/upload/$fp")).optLong("offset")
        if (offset >= size && size > 0) return@withContext
        val c = open("/v1/upload/$fp?s=$session&offset=$offset", "PUT")
        c.doOutput = true
        c.setFixedLengthStreamingMode(size - offset)
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.readTimeout = 120_000
        source().use { ins ->
            var skip = offset
            while (skip > 0) { val n = ins.skip(skip); if (n <= 0) throw SyncException("Couldn't resume the upload"); skip -= n }
            c.outputStream.use { out -> copy(ins, out, progress) }
        }
        check(c)
    }

    suspend fun finish(session: String, plays: Map<String, PlayCount>): Map<String, PlayCount> = withContext(Dispatchers.IO) {
        val p = JSONObject()
        plays.forEach { (fp, v) -> p.put(fp, JSONObject().put("plays", v.plays).put("lastPlayed", v.lastPlayed)) }
        val r = post("/v1/sync/finish", JSONObject().put("session", session).put("plays", p))
        val out = HashMap<String, PlayCount>()
        r.optJSONObject("plays")?.let { j -> j.keys().forEach { k -> j.getJSONObject(k).let { out[k] = PlayCount(it.optInt("plays"), it.optLong("lastPlayed")) } } }
        out
    }

    suspend fun art(id: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { check(open("/v1/art/$id")).inputStream.use { it.readBytes() } }.getOrNull()
    }

    private fun copy(ins: InputStream, out: java.io.OutputStream, progress: (Long) -> Unit) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            progress(n.toLong())
        }
    }

    companion object {
        /**
         * Finds PCs: broadcasts the probe on every network (Wi-Fi, USB tethering…) and also
         * tries 127.0.0.1, which reaches the PC when it set up `adb reverse` over a cable.
         */
        suspend fun discover(timeoutMs: Int = 1500): List<PcHello> = withContext(Dispatchers.IO) {
            val found = LinkedHashMap<String, PcHello>()
            runCatching {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    s.soTimeout = 250
                    val probe = SyncProtocol.PROBE.toByteArray()
                    val targets = buildSet {
                        add(InetAddress.getByName("255.255.255.255"))
                        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }
                            .flatMap { it.interfaceAddresses }.mapNotNull { it.broadcast }.forEach { add(it) }
                    }
                    val deadline = System.currentTimeMillis() + timeoutMs
                    var sentAt = 0L
                    val buf = ByteArray(1024)
                    while (System.currentTimeMillis() < deadline) {
                        if (System.currentTimeMillis() - sentAt > 500) {
                            targets.forEach { runCatching { s.send(DatagramPacket(probe, probe.size, it, SyncProtocol.DISCOVERY_PORT)) } }
                            sentAt = System.currentTimeMillis()
                        }
                        try {
                            val p = DatagramPacket(buf, buf.size)
                            s.receive(p)
                            val j = JSONObject(String(p.data, 0, p.length))
                            if (j.optString("app") == "kororythm") {
                                val h = PcHello.parse(j, p.address.hostAddress ?: continue)
                                found[h.fp] = h
                            }
                        } catch (_: SocketTimeoutException) {
                        } catch (_: org.json.JSONException) {
                        }
                    }
                }
            }
            if (found.isEmpty()) runCatching { // cable (adb reverse)
                val c = SyncClient("127.0.0.1", SyncProtocol.PORT, null)
                val h = c.hello()
                c.seenFp?.let { fp -> found[fp] = h.copy(name = h.name + " (USB)", fp = fp) }
            }
            found.values.toList()
        }
    }
}
