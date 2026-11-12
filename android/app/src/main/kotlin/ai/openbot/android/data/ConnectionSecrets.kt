package ai.openbot.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Connection bearer tokens stay in encrypted app-private preferences, outside SQLite and AppState. */
class ConnectionSecrets(context:Context) {
    private val preferences=context.applicationContext.getSharedPreferences("connection-secrets",Context.MODE_PRIVATE)
    fun hasToken(id:String)=preferences.contains(id)
    fun token(id:String):String? {
        val encoded=preferences.getString(id,null)?:return null
        val bytes=Base64.decode(encoded,Base64.NO_WRAP)
        check(bytes.size>28){"The connection credential could not be opened. Save it again."}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        cipher.updateAAD(id.toByteArray())
        return cipher.doFinal(bytes.copyOfRange(12,bytes.size)).toString(Charsets.UTF_8)
    }
    fun save(id:String,token:String?) {
        if(token.isNullOrBlank()){check(preferences.edit().remove(id).commit());return}
        require(token.none{it=='\r'||it=='\n'}){"The connection credential must be a single line."}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(id.toByteArray())
        val bytes=cipher.iv+cipher.doFinal(token.trim().toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString(id,Base64.encodeToString(bytes,Base64.NO_WRAP)).commit()){"The connection credential could not be saved."}
    }
    fun remove(id:String){check(preferences.edit().remove(id).commit()){"The connection credential could not be removed."}}
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey("openbot-connection-keys",null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("openbot-connection-keys",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setBlockModes(KeyProperties.BLOCK_MODE_GCM).build())
        }.generateKey()
    }
}
