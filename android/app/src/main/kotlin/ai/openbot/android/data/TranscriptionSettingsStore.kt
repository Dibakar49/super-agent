package ai.openbot.android.data

import android.content.Context
import ai.openbot.core.*

/** Independent destination and credential: voice recordings never inherit a model provider's key. */
class TranscriptionSettingsStore(context:Context):ModelConfiguration {
    private val store=ModelSettingsStore(context,"transcription-settings","openbot-transcription-key",ProviderSettings(model="gpt-4o-mini-transcribe"))
    override fun settings()=store.settings()
    override fun snapshot()=store.snapshot()
    override fun hasKey()=store.hasKey()
    override fun apiKey()=store.apiKey()
    fun configured()=store.configured()
    fun save(baseUrl:String,model:String,newKey:String?,clearKey:Boolean) {
        ai.openbot.android.inference.transcriptionEndpoint(baseUrl,model)
        store.save(ProviderSettings(baseUrl=baseUrl.trim(),model=model.trim()),newKey,clearKey)
    }
    fun clear()=store.clear()
}
