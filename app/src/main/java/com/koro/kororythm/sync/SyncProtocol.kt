package com.koro.kororythm.sync

import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Sync protocol v1 (protocol/SYNC.md): the PC is the server, the phone the client. */
object SyncProtocol {
    const val VERSION = 1
    const val PORT = 47800
    const val DISCOVERY_PORT = 47801
    const val PROBE = "KORORYTHM-DISCOVER 1"

    /** hex HMAC-SHA256(code, label|nonce|fp): proves knowing the pairing code without sending it. */
    fun proof(code: String, label: String, nonce: String, fp: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(code.toByteArray(), "HmacSHA256"))
        return Fingerprint.hex(mac.doFinal("$label|$nonce|$fp".toByteArray()))
    }
}

/** A PC found on the network (or typed in). */
data class PcHello(val name: String, val host: String, val port: Int, val fp: String) {
    companion object {
        fun parse(j: JSONObject, host: String) = PcHello(j.optString("name", host), host, j.optInt("port", SyncProtocol.PORT), j.getString("fp"))
    }
}

data class ManifestTrack(
    val fp: String, val name: String, val size: Long, val title: String, val artist: String?, val album: String?,
    val durationMs: Long, val plays: Int, val lastPlayed: Long,
) {
    fun json() = JSONObject().put("fp", fp).put("name", name).put("size", size).put("title", title)
        .putOpt("artist", artist).putOpt("album", album).put("durationMs", durationMs).put("plays", plays).put("lastPlayed", lastPlayed)
}

data class ManifestPlaylist(val name: String, val items: List<String>, val modified: Long) {
    fun json() = JSONObject().put("name", name).put("items", JSONArray(items)).put("modified", modified)
    companion object {
        fun parse(j: JSONObject) = ManifestPlaylist(j.getString("name"), j.optJSONArray("items").strings(), j.optLong("modified"))
    }
}

data class SyncOptions(
    val noDownload: Boolean = false,
    val noUpload: Boolean = false,
    val only: List<String> = emptyList(),
    val budget: Long = 0,
    val maxBits: Int = 0,
) {
    fun json() = JSONObject().put("noDownload", noDownload).put("noUpload", noUpload).put("only", JSONArray(only))
        .put("budget", budget).put("maxBits", maxBits)
}

data class Transfer(
    val fp: String, val name: String, val folder: String, val size: Long, val title: String,
    val artist: String?, val album: String?, val artId: String?, val convert: Boolean,
) {
    companion object {
        fun parse(j: JSONObject) = Transfer(
            j.getString("fp"), j.getString("name"), j.optString("folder", ""), j.optLong("size"), j.optString("title"),
            j.optStringOrNull("artist"), j.optStringOrNull("album"), j.optStringOrNull("artId"), j.optBoolean("convert"),
        )
    }
}

data class Plan(
    val session: String, val pcName: String, val toPhone: List<Transfer>, val toPc: List<Transfer>,
    val playlists: List<ManifestPlaylist>, val skipped: Int,
) {
    val bytes get() = toPhone.sumOf { it.size } + toPc.sumOf { it.size }

    companion object {
        fun parse(j: JSONObject) = Plan(
            j.getString("session"), j.optString("pcName"),
            j.optJSONArray("toPhone").objects().map(Transfer::parse),
            j.optJSONArray("toPc").objects().map(Transfer::parse),
            j.optJSONArray("playlists").objects().map(ManifestPlaylist::parse),
            j.optInt("skipped"),
        )
    }
}

data class PlayCount(val plays: Int, val lastPlayed: Long)

fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }
fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
