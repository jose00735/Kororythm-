package com.koro.kororythm.sync

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A PC this phone is paired with. */
data class PairedPc(val name: String, val host: String, val port: Int, val fp: String, val token: String, val lastSync: Long = 0) {
    /** The first 8 hex digits of its key, as the PC's Sync window shows them ("BE66 69F1"). */
    val key get() = (fp.take(4) + " " + fp.drop(4).take(4)).uppercase()

    fun json() = JSONObject().put("name", name).put("host", host).put("port", port).put("fp", fp).put("token", token).put("lastSync", lastSync)

    companion object {
        fun parse(j: JSONObject) = PairedPc(j.getString("name"), j.getString("host"), j.optInt("port", SyncProtocol.PORT), j.getString("fp"), j.getString("token"), j.optLong("lastSync"))
    }
}

/** What to sync (phase 5 options). */
data class SyncPrefs(
    /** Empty = everything; otherwise only these PC playlists come to the phone. */
    val only: List<String> = emptyList(),
    /** Leave this much free on the phone (bytes); songs past that wait for the next sync. */
    val keepFree: Long = 2L shl 30,
    /** Hi-res FLACs come over as 16-bit FLAC. */
    val to16Bit: Boolean = false,
    /** Send the phone's songs to the PC too. */
    val upload: Boolean = true,
    /** Sync by itself on Wi-Fi while charging. */
    val auto: Boolean = false,
)

/** Paired PCs and sync options, in SharedPreferences. */
class SyncStore(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    /** This phone's id towards the PCs (made once). */
    val deviceId: String get() = p.getString("device", null) ?: UUID.randomUUID().toString().also { p.edit().putString("device", it).apply() }

    val deviceName: String get() = Build.MODEL ?: "Android phone"

    fun pcs(): List<PairedPc> = runCatching { JSONArray(p.getString("pcs", "[]")).objects().map(PairedPc::parse) }.getOrDefault(emptyList())

    fun put(pc: PairedPc) = save(pcs().filter { it.fp != pc.fp } + pc)

    fun forget(fp: String) = save(pcs().filter { it.fp != fp })

    private fun save(list: List<PairedPc>) = p.edit().putString("pcs", JSONArray(list.map { it.json() }).toString()).apply()

    fun prefs(): SyncPrefs = SyncPrefs(
        only = p.getString("only", "")!!.split('\n').filter { it.isNotBlank() },
        keepFree = p.getLong("keepFree", 2L shl 30),
        to16Bit = p.getBoolean("to16", false),
        upload = p.getBoolean("upload", true),
        auto = p.getBoolean("auto", false),
    )

    fun setPrefs(s: SyncPrefs) = p.edit().putString("only", s.only.joinToString("\n")).putLong("keepFree", s.keepFree)
        .putBoolean("to16", s.to16Bit).putBoolean("upload", s.upload).putBoolean("auto", s.auto).apply()
}
