package ai.openbot.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.Serializable

const val MAX_ATTACHMENTS_PER_MESSAGE=8
const val MAX_IMAGE_BYTES=8*1024*1024
const val MAX_FILE_BYTES=1024*1024
const val MAX_EXTRACTED_CHARACTERS=120_000
const val MAX_INLINED_BYTES_PER_RUN=32*1024*1024
val ACCEPTED_IMAGE_MIME=listOf("image/png","image/jpeg","image/gif","image/webp")
val ACCEPTED_TEXT_MIME=listOf("text/plain","text/markdown","text/csv","application/json")

@Serializable enum class AttachmentKind { IMAGE,TEXT,UNSUPPORTED_IMAGE,UNSUPPORTED }
@Serializable data class LocalAttachment(val id:String,val name:String,val mimeType:String,val size:Long,
    val createdAt:String,val stageKey:String?=null,val channelId:String?=null,val messageId:String?=null)

fun mediaTypeOf(mimeType:String)=mimeType.lowercase().substringBefore(';').trim()
fun namesNoFormat(mimeType:String):Boolean {
    val type=mediaTypeOf(mimeType)
    return '/' !in type||type in setOf("application/octet-stream","binary/octet-stream","application/unknown","application/force-download")
}
fun classifyAttachment(mimeType:String):AttachmentKind {
    val type=mediaTypeOf(mimeType)
    return when {
        type in ACCEPTED_IMAGE_MIME->AttachmentKind.IMAGE
        type.startsWith("image/")->AttachmentKind.UNSUPPORTED_IMAGE
        type in ACCEPTED_TEXT_MIME->AttachmentKind.TEXT
        else->AttachmentKind.UNSUPPORTED
    }
}
fun mayBeTruncatedForModel(sizeBytes:Long)=sizeBytes>MAX_EXTRACTED_CHARACTERS
fun shouldClaimPaste(kinds:List<AttachmentKind>,plainText:String)=plainText.isEmpty()&&kinds.isNotEmpty()
fun attachmentUrl(attachmentId:String)="/api/attachments/$attachmentId"

/** Byte-compatible with the original sniffMimeType, including corroborated text/image claims. */
fun sniffAttachmentMime(bytes:ByteArray,claimed:String):String {
    if(bytes.isEmpty())return "application/octet-stream"
    fun signature(vararg values:Int,offset:Int=0)=bytes.size>=offset+values.size&&values.indices.all{(bytes[offset+it].toInt() and 255)==values[it]}
    when {
        signature(0x89,0x50,0x4e,0x47)->return "image/png"
        signature(0xff,0xd8,0xff)->return "image/jpeg"
        signature(0x47,0x49,0x46,0x38)->return "image/gif"
        signature(0x52,0x49,0x46,0x46)&&signature(0x57,0x45,0x42,0x50,offset=8)->return "image/webp"
    }
    val type=mediaTypeOf(claimed)
    fun utf8()=runCatching{Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))}.isSuccess
    if(type in ACCEPTED_TEXT_MIME)return if(utf8())type else "application/octet-stream"
    if(type in ACCEPTED_IMAGE_MIME)return "application/octet-stream"
    if(!namesNoFormat(type))return type
    return if(utf8())"text/plain" else "application/octet-stream"
}

/** The original filename ceiling is 255 UTF-8 bytes, without cutting a code point. */
fun attachmentFilename(name:String):String {
    if(name.length<=85)return name
    val out=StringBuilder();var bytes=0;var index=0
    while(index<name.length) {
        val cp=name.codePointAt(index);val value=String(Character.toChars(cp));val size=when{cp<=0x7f->1;cp<=0x7ff->2;cp<=0xffff->3;else->4}
        if(bytes+size>255)break
        out.append(value);bytes+=size;index+=Character.charCount(cp)
    }
    return out.toString()
}
fun validateAttachment(name:String,mime:String,size:Long) {
    val kind=classifyAttachment(mime)
    when(kind) {
        AttachmentKind.UNSUPPORTED_IMAGE->error(if(mime=="image/svg+xml")"'$name' can't be attached. SVG files aren't accepted." else "'$name' can't be attached. OpenBot can't read $mime images.")
        AttachmentKind.UNSUPPORTED->error("'$name' isn't a file type OpenBot can read${if(namesNoFormat(mime))"" else " ($mime)"}.")
        AttachmentKind.IMAGE->require(size<=MAX_IMAGE_BYTES){"'$name' is larger than the 8MB limit for images."}
        AttachmentKind.TEXT->require(size<=MAX_FILE_BYTES){"'$name' is larger than the 1MB limit for files."}
    }
    require(size>0){"'$name' is empty."}
}
fun extractAttachmentText(bytes:ByteArray):String {
    val full=bytes.toString(Charsets.UTF_8)
    if(full.length<=MAX_EXTRACTED_CHARACTERS)return full
    var end=MAX_EXTRACTED_CHARACTERS
    if(full[end-1].isHighSurrogate())end--
    return full.substring(0,end)+"\n\n[attachment truncated at $end characters]"
}
