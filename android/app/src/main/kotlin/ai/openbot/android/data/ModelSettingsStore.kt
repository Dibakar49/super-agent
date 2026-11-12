package ai.openbot.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ai.openbot.core.ProviderSettings
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Secret values never enter AppState, logs, backups or repository files. */
class ModelSettingsStore(context: Context,private val preferenceName:String="model-settings",private val keyAlias:String="openbot-model-key",private val defaults:ProviderSettings=ProviderSettings()): ai.openbot.core.ModelConfiguration {
    companion object {private val credentialLock=Any()}
    override fun snapshot():Pair<ProviderSettings,String?> = synchronized(credentialLock){settings() to apiKey()}
    private val preferences = context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    override fun settings(): ProviderSettings = preferences.getString("settings", null)?.let { json.decodeFromString(it) } ?: defaults
    fun configured()=preferences.contains("settings")
    fun clear()=synchronized(credentialLock){check(preferences.edit().clear().commit()){ "The settings could not be removed." }}
    override fun hasKey(): Boolean = preferences.contains("encrypted-key")

    override fun apiKey(): String? {
        val encoded = preferences.getString("encrypted-key", null) ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > 28) { "The saved key could not be opened. Save it again in Model settings." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }

    fun save(settings: ProviderSettings, newKey: String?, clearKey: Boolean) = synchronized(credentialLock) {
        val old = settings()
        // A key is never silently reused for a different model API origin/path.
        val changedEndpoint = old.baseUrl.trimEnd('/') != settings.baseUrl.trimEnd('/')
        val edit = preferences.edit().putString("settings", json.encodeToString(settings))
        if (clearKey || changedEndpoint) edit.remove("encrypted-key")
        if (!newKey.isNullOrBlank()) {
            require(newKey.none { it == '\r' || it == '\n' }) { "The key must be a single line." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
            val bytes = cipher.iv + cipher.doFinal(newKey.trim().toByteArray(Charsets.UTF_8))
            edit.putString("encrypted-key", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
        check(edit.commit()) { "The model settings could not be saved." }
    }

    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
