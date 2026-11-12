package ai.openbot.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class QuestionPart(val header:String,val question:String,val options:List<String> = emptyList())
@Serializable data class LocalQuestion(val id:String,val turnId:String,val channelId:String,val botId:String,
    val call:ToolCall,val question:String,val why:String?=null,val parts:List<QuestionPart> = emptyList(),
    val createdAt:String,val answer:String?=null)

val askPersonTool:AgentTool by lazy {
    val source=Json.parseToJsonElement(requireNotNull(QuestionPart::class.java.getResourceAsStream("/question-tool.json")).bufferedReader().use{it.readText()}).jsonObject
    AgentTool(source["name"]!!.jsonPrimitive.content,source["description"]!!.jsonPrimitive.content,source["inputSchema"]!!.jsonObject)
}

fun parseQuestion(call:ToolCall):Triple<String,String?,List<QuestionPart>> {
    require(call.name==askPersonTool.name)
    validateToolArguments(askPersonTool,call.arguments)
    val question=call.arguments["question"]!!.jsonPrimitive.content.trim()
    require(question.isNotEmpty()&&question.length<=6000){"Say what you need a person to answer, in 1–6000 characters."}
    val why=call.arguments["why"]?.jsonPrimitive?.content?.trim()
    require(why==null||why.length<=6000){"Explain why in at most 6000 characters."}
    val parts=call.arguments["parts"]?.jsonArray?.map{element->
        val part=element.jsonObject
        QuestionPart(part["header"]!!.jsonPrimitive.content.trim(),part["question"]!!.jsonPrimitive.content.trim(),
            part["options"]?.jsonArray?.map{it.jsonPrimitive.content.trim()}.orEmpty()).also {
            require(it.header.isNotEmpty()&&it.header.length<=40&&it.question.isNotEmpty()&&it.question.length<=500)
            require(it.options.size<=6&&it.options.all{option->option.isNotEmpty()&&option.length<=120})
        }
    }.orEmpty()
    require(parts.size<=6&&(call.arguments["parts"]==null||parts.isNotEmpty()))
    return Triple(question,why,parts)
}
fun questionResponseMessage(question:String,response:String)="In response to your question: $question\n\n$response"
fun partsAnswer(parts:List<QuestionPart>,selected:List<String>,typed:List<String>):String {
    require(parts.isNotEmpty()&&selected.size==parts.size&&typed.size==parts.size)
    return parts.mapIndexed{index,part->
        val values=listOf(selected[index].trim(),typed[index].trim()).filter{it.isNotEmpty()}
        require(values.isNotEmpty()){"Answer each question before sending."}
        "${part.header}: ${values.joinToString(", ") }"
    }.joinToString("\n")
}
