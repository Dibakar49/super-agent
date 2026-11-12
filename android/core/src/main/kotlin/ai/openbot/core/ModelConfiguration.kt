package ai.openbot.core

/** Runtime only reads credentials; their Android storage stays outside public UI state. */
interface ModelConfiguration {
    fun settings():ProviderSettings
    fun hasKey():Boolean
    fun apiKey():String?
    fun snapshot():Pair<ProviderSettings,String?> = settings() to apiKey()
}
