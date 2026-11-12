package ai.openbot.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import ai.openbot.core.*
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Files stay private to this app; filenames from document providers are display metadata only. */
class AttachmentStore(context:Context,private val store:LocalStore) {
    private val directory=File(context.applicationContext.filesDir,"attachments")
    private val json=Json{ignoreUnknownKeys=true}
    private fun rows(selection:String="",args:Array<String> = emptyArray()):List<LocalAttachment> =
        store.readableDatabase.rawQuery("SELECT payload FROM local_attachments $selection ORDER BY created_at,id",args).use{cursor->
            buildList{while(cursor.moveToNext())add(json.decodeFromString<LocalAttachment>(cursor.getString(0)))}
        }
    fun staged(key:String)=rows("WHERE stage_key=?",arrayOf(key))
    fun conversation(id:String)=rows("WHERE channel_id=?",arrayOf(id))
    fun get(id:String)=rows("WHERE id=?",arrayOf(id)).singleOrNull()
    private fun file(id:String):File {
        require(runCatching{UUID.fromString(id).toString()==id}.getOrDefault(false)){"Invalid attachment identity."}
        return File(directory,id)
    }
    fun open(id:String,channelId:String):InputStream {
        val attachment=get(id)?:error("This attachment is no longer available.")
        require(attachment.channelId==channelId&&attachment.messageId!=null){"This attachment does not belong to the conversation."}
        return file(id).inputStream()
    }
    fun preview(id:String,stageKey:String?,channelId:String?):File? {
        val attachment=get(id)?:return null
        if(!(attachment.stageKey!=null&&attachment.stageKey==stageKey||attachment.channelId!=null&&attachment.channelId==channelId))return null
        return file(id).takeIf{it.isFile}
    }
    private fun save(attachment:LocalAttachment) {
        val values=ContentValues().apply {
            put("payload",json.encodeToString(attachment));put("stage_key",attachment.stageKey)
            put("channel_id",attachment.channelId);put("message_id",attachment.messageId)
            put("created_at",attachment.createdAt)
        }
        if(store.writableDatabase.update("local_attachments",values,"id=?",arrayOf(attachment.id))==0) {
            values.put("id",attachment.id);store.writableDatabase.insertOrThrow("local_attachments",null,values)
        }
    }
    fun stage(key:String,name:String,claimed:String,input:InputStream,now:Instant=Instant.now()):LocalAttachment = input.use { synchronized(lock) {
        require(key.isNotBlank()&&key.length<=250)
        require(staged(key).size<MAX_ATTACHMENTS_PER_MESSAGE){"You can attach $MAX_ATTACHMENTS_PER_MESSAGE files to a message."}
        require(rows("WHERE stage_key IS NOT NULL").size<32){"Finish or remove some waiting attachments before adding more."}
        directory.mkdirs();val id=UUID.randomUUID().toString();val target=file(id);val temporary=File(directory,".$id.part")
        try {
            var count=0L
            input.use{source->temporary.outputStream().use{output->
                val buffer=ByteArray(8192)
                while(true) {
                    val size=source.read(buffer);if(size<0)break
                    count+=size;require(count<=MAX_IMAGE_BYTES){"'${attachmentFilename(name)}' is larger than the 8MB limit for images."}
                    output.write(buffer,0,size)
                }
            }}
            val bytes=temporary.readBytes();val mime=sniffAttachmentMime(bytes,claimed);val keptName=attachmentFilename(name)
            validateAttachment(keptName,mime,count)
            val channelId=key.takeIf{it.startsWith("conversation:")}?.removePrefix("conversation:")
            require(channelId==null||store.conversations().any{it.id==channelId}){"This conversation no longer exists."}
            val attachment=LocalAttachment(id,keptName,mime,count,now.toString(),stageKey=key,channelId=channelId)
            check(temporary.renameTo(target)){"Could not save this attachment."}
            val db=store.writableDatabase;db.beginTransaction()
            try {
                require(staged(key).size<MAX_ATTACHMENTS_PER_MESSAGE){"You can attach $MAX_ATTACHMENTS_PER_MESSAGE files to a message."}
                save(attachment);db.setTransactionSuccessful()
            }finally{db.endTransaction()}
            LocalDatabaseChanges.changed();attachment
        }catch(failure:Throwable){target.delete();throw failure}
        finally{temporary.delete()}
    }}
    fun validateSend(ids:List<String>,stageKey:String):List<LocalAttachment> {
        require(ids.distinct().size==ids.size&&ids.size<=MAX_ATTACHMENTS_PER_MESSAGE){"You can attach $MAX_ATTACHMENTS_PER_MESSAGE files to a message."}
        val selected=ids.map{id->get(id)?.takeIf{it.stageKey==stageKey&&it.messageId==null}?:error("A selected attachment is no longer waiting to send.")}
        require(selected.sumOf{it.size}<=MAX_INLINED_BYTES_PER_RUN){"This message's attachments exceed the 32MB limit. Send fewer files in each message."}
        selected.forEach{require(file(it.id).isFile){"'${it.name}' is no longer available. Attach it again."}}
        return selected
    }
    /** Called inside the same transaction that saves the person's message. */
    fun bind(ids:List<String>,stageKey:String,channelId:String,messageId:String) {
        require(store.writableDatabase.inTransaction()){"Attachments must commit with their message."}
        val message=store.messages(channelId).firstOrNull{it.id==messageId&&it.role=="user"}?:error("The attachment message does not exist.")
        require(message.conversationId==channelId)
        validateSend(ids,stageKey).forEach{save(it.copy(stageKey=null,channelId=channelId,messageId=messageId))}
    }
    fun remove(id:String,stageKey:String)=synchronized(lock) {
        val attachment=get(id)?:return@synchronized
        require(attachment.stageKey==stageKey&&attachment.messageId==null){"An attachment already sent cannot be removed from its message."}
        require(store.writableDatabase.delete("local_attachments","id=? AND stage_key=?",arrayOf(id,stageKey))==1){"This attachment is no longer waiting to send."}
        file(id).delete();LocalDatabaseChanges.changed()
    }
    fun deleteUnreferenced(ids:List<String>)=synchronized(lock) {
        ids.filter{get(it)==null}.forEach{file(it).delete()}
    }
    fun reap(now:Instant=Instant.now())=synchronized(lock) {
        val cutoff=now.minusSeconds(24*60*60)
        rows("WHERE stage_key IS NOT NULL").filter{Instant.parse(it.createdAt)<cutoff}.forEach{remove(it.id,requireNotNull(it.stageKey))}
        val live=rows().map{it.id}.toSet()
        directory.listFiles().orEmpty().filter{it.name !in live&&it.lastModified()<cutoff.toEpochMilli()}.forEach{it.delete()}
    }
    companion object {
        private val lock=Any()
        fun createTables(db:SQLiteDatabase) {
            db.execSQL("CREATE TABLE local_attachments(id TEXT PRIMARY KEY,stage_key TEXT,channel_id TEXT REFERENCES conversations(id) ON DELETE CASCADE,message_id TEXT REFERENCES messages(id) ON DELETE CASCADE,created_at TEXT NOT NULL,payload TEXT NOT NULL)")
            db.execSQL("CREATE INDEX local_attachments_stage ON local_attachments(stage_key)")
            db.execSQL("CREATE INDEX local_attachments_channel ON local_attachments(channel_id)")
        }
    }
}
