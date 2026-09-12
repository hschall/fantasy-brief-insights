package com.aviato.fantasybrief.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed storage for the two ESPN cookies.
 *
 * Values are AES-256-GCM encrypted with a key that lives inside the Android
 * Keystore and is never exported. The ciphertext lands in ordinary
 * SharedPreferences, which is fine because it is ciphertext.
 */
class SecretStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- public API -------------------------------------------------------

    val espnS2: String? get() = read(KEY_S2)
    val swid: String? get() = read(KEY_SWID)

    /** Base URL of the analysis endpoint, no trailing slash. */
    val apiUrl: String? get() = read(KEY_API_URL)
    val apiKey: String? get() = read(KEY_API_KEY)

    val hasApi: Boolean
        get() = !apiUrl.isNullOrBlank() && !apiKey.isNullOrBlank()

    fun saveApi(url: String, key: String) {
        prefs.edit()
            .putString(KEY_API_URL, encrypt(cleanUrl(url)))
            .putString(KEY_API_KEY, encrypt(key))
            .apply()
    }

    val hasCredentials: Boolean
        get() = !espnS2.isNullOrBlank() && !swid.isNullOrBlank()

    fun save(espnS2: String, swid: String) {
        prefs.edit()
            .putString(KEY_S2, encrypt(espnS2))
            .putString(KEY_SWID, encrypt(swid))
            .apply()
    }

    /**
     * First whitespace-free token that looks like a URL. A pasted value
     * arrived as the host repeated across a line break, which OkHttp
     * rejected with an opaque "invalid URL host".
     */
    private fun cleanUrl(raw: String): String {
        val token = raw.split(Regex("\\s+"))
            .firstOrNull { it.startsWith("http", ignoreCase = true) }
            ?: raw.trim()
        return token.trim().trimEnd('/')
    }

    fun clear() = prefs.edit().clear().apply()

    /** Exactly what ESPN wants in the Cookie header. SWID keeps its braces. */
    fun cookieHeader(): String = "espn_s2=$espnS2; SWID=$swid"

    // ---- crypto -----------------------------------------------------------

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    /** Packs [iv length][iv][ciphertext] into one base64 blob. */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))

        val packed = ByteArray(1 + iv.size + body.size)
        packed[0] = iv.size.toByte()
        System.arraycopy(iv, 0, packed, 1, iv.size)
        System.arraycopy(body, 0, packed, 1 + iv.size, body.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun read(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            val packed = Base64.decode(stored, Base64.NO_WRAP)
            val ivLength = packed[0].toInt()
            val iv = packed.copyOfRange(1, 1 + ivLength)
            val body = packed.copyOfRange(1 + ivLength, packed.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            // Key invalidated or blob corrupt. Behave as if signed out.
            null
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "fantasy_brief_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFS_NAME = "espn_secrets"
        const val KEY_S2 = "espn_s2"
        const val KEY_SWID = "swid"
        const val KEY_API_URL = "api_url"
        const val KEY_API_KEY = "api_key"
    }
}
