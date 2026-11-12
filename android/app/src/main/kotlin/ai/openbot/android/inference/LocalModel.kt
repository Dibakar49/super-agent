package ai.openbot.android.inference

import android.content.Context
import android.net.Uri
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** MediaPipe executes a user-supplied compatible .task model entirely on the device. */
class LocalModel(context: Context) {
    private val context=context.applicationContext
    private class SharedEngine(val lock:Mutex=Mutex(),var inference:LlmInference?=null,var owners:Int=0)
    private companion object {val engines=mutableMapOf<String,SharedEngine>()}
    private val resourceKey=File(this.context.filesDir,"models/local.task").absolutePath
    private val shared=synchronized(engines){engines.getOrPut(resourceKey){SharedEngine()}.also{it.owners++}}
    private val lock get()=shared.lock
    private var inference:LlmInference?
        get()=shared.inference
        set(value){shared.inference=value}
    private var released=false
    private val file get() = File(resourceKey)
    val installed: Boolean get() = file.isFile && file.length() > 0

    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val operation = currentCoroutineContext()
        lock.withLock {
            check(!released){"This model runtime has been closed."}
            val target = file
            target.parentFile!!.mkdirs()
            val temporary = File(target.parentFile, "import.pending.task")
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "The selected model could not be opened." }
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var total = 0L
                        while (true) {
                            operation.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 8L * 1024 * 1024 * 1024) { "Model files larger than 8 GB are unsupported." }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        require(total > 0) { "The selected file is empty." }
                    }
                }
                // Validate before replacing the working model. Native loading can require substantial RAM.
                inference?.close(); inference = null
                create(temporary).close()
                check(temporary.renameTo(target)) { "Could not store the model. The previous model has been retained." }
                digest.digest().joinToString("") { "%02x".format(it) }
            } finally { temporary.delete() }
        }
    }

    suspend fun generate(prompt: String): String = withContext(Dispatchers.Default) {
        lock.withLock {
            check(!released){"This model runtime has been closed."}
            check(installed) { "Import a compatible on-device .task model in Model settings first." }
            val engine = inference ?: create(file).also { inference = it }
            engine.generateResponse(prompt).also { check(it.isNotBlank()) { "The model returned an empty answer." } }
        }
    }

    suspend fun close() = withContext(Dispatchers.Default) {
        lock.withLock {
            if(released)return@withLock
            released=true
            val last=synchronized(engines){--shared.owners==0}
            if(last)try {inference?.close()}finally {
                inference=null
                synchronized(engines){if(shared.owners==0&&engines[resourceKey]===shared)engines.remove(resourceKey)}
            }
        }
    }

    private fun create(model: File): LlmInference = LlmInference.createFromOptions(
        context,
        LlmInference.LlmInferenceOptions.builder()
            .setModelPath(model.absolutePath)
            .setMaxTokens(4096)
            .build()
    )
}
