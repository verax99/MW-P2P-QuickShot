package kz.monkeywrench.quickshot

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecretStore {
    private const val ALIAS = "quickshot_bot_token"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    fun setToken(context: Context, token: String) {
        require(Regex("^[0-9]+:[A-Za-z0-9_-]+$").matches(token)) { "Неверный формат токена" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val data = cipher.iv + ciphertext
        context.getSharedPreferences("secret", Context.MODE_PRIVATE).edit().putString("token", Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }
    fun getToken(context: Context): String? {
        return try {
            val encoded = context.getSharedPreferences("secret", Context.MODE_PRIVATE).getString("token", null) ?: return null
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }
    fun hasToken(context: Context) = !getToken(context).isNullOrEmpty()
}
