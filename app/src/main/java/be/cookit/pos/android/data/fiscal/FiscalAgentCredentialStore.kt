package be.cookit.pos.android.data.fiscal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * Stores the Cookit Fiscal Agent device token encrypted with an Android Keystore AES/GCM key.
 *
 * This store is only for Cookit backend device credentials. Checkbox/Eutronix provider secrets or
 * client certificates remain outside this generic runtime until the certified security profile is known.
 */
class FiscalAgentCredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(credentials: FiscalAgentCredentials) {
        require(credentials.configured) { "Fiscal Agent credentials incomplete" }

        val plain = JSONObject()
            .put("device_id", credentials.deviceId.trim())
            .put("device_token", credentials.deviceToken)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plain)

        prefs.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_PAYLOAD, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_DEVICE_HINT, credentials.deviceId.trim())
            .apply()
    }

    fun load(): FiscalAgentCredentials? {
        val iv = prefs.getString(KEY_IV, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val encrypted = prefs.getString(KEY_PAYLOAD, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null

        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val json = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
            FiscalAgentCredentials(
                deviceId = json.optString("device_id"),
                deviceToken = json.optString("device_token")
            ).takeIf { it.configured }
        }.getOrElse {
            // A restored preference blob cannot be decrypted if the Keystore key was not restored.
            // Fail closed and remove only the unusable credential blob.
            clear()
            null
        }
    }

    fun configured(): Boolean = prefs.contains(KEY_IV) && prefs.contains(KEY_PAYLOAD)

    fun deviceHint(): String = prefs.getString(KEY_DEVICE_HINT, "").orEmpty()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        private const val PREFS = "cookit_fiscal_agent_secure_v1"
        private const val KEY_IV = "iv"
        private const val KEY_PAYLOAD = "payload"
        private const val KEY_DEVICE_HINT = "device_hint"
        private const val KEY_ALIAS = "cookit_fiscal_agent_credentials_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
