package com.gamebooster.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair

class SecureStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Auth tokens and the device WireGuard private key live in encrypted prefs only. */
class SessionStore(context: Context) {
    private val prefs: SharedPreferences?
    private val initializationFailure: Exception?

    init {
        var created: SharedPreferences? = null
        var failure: Exception? = null
        try {
            created = EncryptedSharedPreferences.create(
                context.applicationContext,
                "secure_store",
                MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Do not delete preferences or regenerate keys here: unreadable storage may
            // still contain the user's only valid device credentials.
            failure = e
        }
        prefs = created
        initializationFailure = failure
    }

    private fun requirePrefs(): SharedPreferences = prefs
        ?: throw SecureStorageException(
            "Encrypted storage is unavailable. Existing credentials were not reset; resolve device Keystore access before reconnecting.",
            initializationFailure
        )

    fun load(): Session? {
        val p = requirePrefs()
        try {
            val access = p.getString("at", null)
            val refresh = p.getString("rt", null)
            if (access == null && refresh == null) return null
            if (access.isNullOrBlank() || refresh.isNullOrBlank()) {
                throw SecureStorageException("Saved sign-in data is incomplete. It was left unchanged for recovery.")
            }
            return Session(
                access,
                refresh,
                p.getString("uid", "") ?: "",
                p.getString("em", "") ?: ""
            )
        } catch (e: SecureStorageException) {
            throw e
        } catch (e: Exception) {
            throw SecureStorageException("Encrypted storage could not be read. Existing credentials were left unchanged.", e)
        }
    }

    fun save(s: Session) {
        val saved = try {
            requirePrefs().edit()
                .putString("at", s.accessToken)
                .putString("rt", s.refreshToken)
                .putString("uid", s.userId)
                .putString("em", s.email)
                .commit()
        } catch (e: Exception) {
            throw SecureStorageException("Sign-in data could not be saved securely.", e)
        }
        if (!saved) throw SecureStorageException("Sign-in data could not be saved securely.")
    }

    fun clearSession() {
        val cleared = try {
            requirePrefs().edit().remove("at").remove("rt").remove("uid").remove("em").commit()
        } catch (e: Exception) {
            throw SecureStorageException("Sign-in data could not be cleared from encrypted storage.", e)
        }
        if (!cleared) throw SecureStorageException("Sign-in data could not be cleared from encrypted storage.")
    }

    fun deviceKeyPair(): KeyPair {
        val p = requirePrefs()
        val existing = try {
            p.getString("wg_priv", null)
        } catch (e: Exception) {
            throw SecureStorageException("The saved WireGuard key cannot be read. It was not replaced.", e)
        }
        if (existing != null) {
            return try {
                KeyPair(Key.fromBase64(existing))
            } catch (e: Exception) {
                throw SecureStorageException("The saved WireGuard key is invalid or inaccessible. It was not replaced.", e)
            }
        }

        val generated = KeyPair()
        val saved = try {
            p.edit().putString("wg_priv", generated.privateKey.toBase64()).commit()
        } catch (e: Exception) {
            throw SecureStorageException("A new WireGuard key could not be stored securely.", e)
        }
        if (!saved) throw SecureStorageException("A new WireGuard key could not be stored securely.")
        return generated
    }
}
