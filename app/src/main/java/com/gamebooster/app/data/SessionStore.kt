package com.gamebooster.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair

data class LastConnection(val gamePackage: String, val serverId: String)

/** Auth tokens and the device WireGuard private key live in encrypted prefs only. */
class SessionStore(private val context: Context) {
    private val prefs: SharedPreferences = open()

    private fun create(): SharedPreferences = EncryptedSharedPreferences.create(
        context, FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** Keystore/prefs corruption happens on some devices; reset instead of crashing.
     *  Consequence: user signs in again and a new device key is generated. */
    private fun open(): SharedPreferences = try { create() } catch (e: Exception) {
        context.deleteSharedPreferences(FILE)
        create()
    }

    fun load(): Session? {
        val a = prefs.getString("at", null) ?: return null
        val r = prefs.getString("rt", null) ?: return null
        return Session(a, r, prefs.getString("uid", "") ?: "", prefs.getString("em", "") ?: "")
    }

    fun save(s: Session) = prefs.edit()
        .putString("at", s.accessToken).putString("rt", s.refreshToken)
        .putString("uid", s.userId).putString("em", s.email).apply()

    fun clearSession() = prefs.edit().remove("at").remove("rt").remove("uid").remove("em").apply()

    fun deviceKeyPair(): KeyPair {
        val existing = prefs.getString("wg_priv", null)
        if (existing != null) return KeyPair(Key.fromBase64(existing))
        val kp = KeyPair()
        prefs.edit().putString("wg_priv", kp.privateKey.toBase64()).apply()
        return kp
    }

    fun saveLast(gamePackage: String, serverId: String) =
        prefs.edit().putString("last_game", gamePackage).putString("last_server", serverId).apply()

    fun loadLast(): LastConnection? {
        val g = prefs.getString("last_game", null) ?: return null
        val s = prefs.getString("last_server", null) ?: return null
        return LastConnection(g, s)
    }

    fun clearLast() = prefs.edit().remove("last_game").remove("last_server").apply()

    /** Per-game preferred server (server id), kept on device. */
    fun gameServerPrefs(): Map<String, String> =
        prefs.all.entries.filter { it.key.startsWith("pref_") && it.value is String }
            .associate { it.key.removePrefix("pref_") to it.value as String }

    fun setGameServerPref(gamePackage: String, serverId: String?) {
        val e = prefs.edit()
        if (serverId == null) e.remove("pref_$gamePackage") else e.putString("pref_$gamePackage", serverId)
        e.apply()
    }

    fun autoReconnect(): Boolean = prefs.getBoolean("auto_reconnect", true)
    fun setAutoReconnect(on: Boolean) = prefs.edit().putBoolean("auto_reconnect", on).apply()

    private companion object { const val FILE = "secure_store" }
}
