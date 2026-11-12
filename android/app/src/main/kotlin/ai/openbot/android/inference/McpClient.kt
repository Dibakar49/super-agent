package ai.openbot.android.inference

import ai.openbot.core.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Direct HTTPS MCP Streamable HTTP transport. A session and its credential never follow redirects. */
interface McpSession {
    suspend fun initialize():String
    suspend fun listTools():List<McpTool>
    suspend fun call(tool:McpTool,args:JsonObject):JsonObject
    suspend fun close()
}
class McpClient(private val connection:LocalConnection,private val token:String?):McpSession {
    private var session:String?=null
    private var initialized=false
    private var protocolVersion=MCP_VERSION
    private suspend fun post(body:JsonObject,id:String?):JsonObject?=withContext(Dispatchers.IO){coroutineScope {
        require(token==null||token.none{it=='\r'||it=='\n'}){"The connection credential must be a single line."}
        val http=mcpEndpoint(connection.url).toURL().openConnection() as HttpsURLConnection
        val watcher=launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED){try{awaitCancellation()}finally{http.disconnect()}}
        try {
            http.requestMethod="POST";http.instanceFollowRedirects=false;http.connectTimeout=20_000;http.readTimeout=120_000
            http.setRequestProperty("Content-Type","application/json");http.setRequestProperty("Accept","application/json, text/event-stream")
            if(initialized)http.setRequestProperty("MCP-Protocol-Version",protocolVersion)
            session?.let{http.setRequestProperty("Mcp-Session-Id",it)}
            token?.takeIf{it.isNotBlank()}?.let{http.setRequestProperty("Authorization","Bearer $it")}
            http.doOutput=true
            val bytes=body.toString().toByteArray(Charsets.UTF_8);http.setFixedLengthStreamingMode(bytes.size)
            http.outputStream.use{it.write(bytes)}
            val status=http.responseCode
            check(status in 200..299) {
                when(status){401,403->"The MCP server refused the connection credential.";in 300..399->"The MCP server redirected the request. Enter its final HTTPS URL.";else->"The MCP request failed (HTTP $status)."}
            }
            http.getHeaderField("Mcp-Session-Id")?.let{value->require(value.length<=4096&&value.all{it.code in 33..126}){"The MCP server returned an invalid session identity."};session=value}
            if(id==null)return@coroutineScope null
            check(status!=202){"The MCP server accepted the request without returning its result. This response mode is not supported yet."}
            http.inputStream.use{input->readMcpStream(input,http.contentType.orEmpty(),id)}
        }finally{watcher.cancel();http.disconnect()}
    }}
    override suspend fun initialize():String {
        val id=UUID.randomUUID().toString()
        val result=requireNotNull(post(mcpInitialize(id),id))
        protocolVersion=result["protocolVersion"]?.jsonPrimitive?.contentOrNull?.takeIf{it in MCP_HTTP_VERSIONS}?:error("This MCP server uses an unsupported protocol version.")
        initialized=true
        post(buildJsonObject{put("jsonrpc","2.0");put("method","notifications/initialized")},null)
        return result["serverInfo"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull?:connection.name
    }
    override suspend fun listTools():List<McpTool> {
        check(initialized){"Initialize the MCP connection first."}
        var descriptionBudget=1_000_000
        val tools=mutableListOf<McpTool>();val seen=mutableSetOf<String>();var cursor:String?=null
        do {
            val id=UUID.randomUUID().toString()
            val params=buildJsonObject{cursor?.let{put("cursor",it)}}
            val reply=requireNotNull(post(mcpRequest(id,"tools/list",params),id))
            val items=reply["tools"] as? JsonArray?:error("The MCP server did not return a tool list.")
            items.forEach{item->
                descriptionBudget-=item.toString().length
                check(descriptionBudget>=0){"The MCP tool descriptions exceeded this connection’s size limit."}
                val obj=item.jsonObject
                val name=obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf{it.isNotBlank()&&it.length<=256}?:error("The MCP server returned an invalid tool name.")
                check(tools.none{it.name==name}){"The MCP server returned duplicate tool names."}
                tools.add(McpTool(name,obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),obj["inputSchema"] as? JsonObject?:error("The MCP server returned an invalid tool schema."),obj["annotations"] as? JsonObject?:buildJsonObject{}))
                check(tools.size<=500){"This MCP connection offers more than 500 tools."}
            }
            cursor=reply["nextCursor"]?.jsonPrimitive?.contentOrNull?.takeIf{it.isNotEmpty()}
            if(cursor!=null)check(seen.add(cursor!!)){"The MCP server repeated a pagination cursor."}
        }while(cursor!=null)
        return tools
    }
    override suspend fun close()=withContext(Dispatchers.IO) {
        val identity=session?:return@withContext
        session=null;initialized=false
        val http=mcpEndpoint(connection.url).toURL().openConnection() as HttpsURLConnection
        try {
            http.requestMethod="DELETE";http.instanceFollowRedirects=false;http.connectTimeout=5000;http.readTimeout=5000
            http.setRequestProperty("Mcp-Session-Id",identity);http.setRequestProperty("MCP-Protocol-Version",protocolVersion)
            token?.takeIf{it.isNotBlank()}?.let{http.setRequestProperty("Authorization","Bearer $it")}
            http.responseCode // Session deletion is optional; a server may answer 405.
        }finally{http.disconnect()}
    }
    override suspend fun call(tool:McpTool,args:JsonObject):JsonObject {
        check(initialized){"Initialize the MCP connection first."}
        val id=UUID.randomUUID().toString()
        return requireNotNull(post(mcpRequest(id,"tools/call",buildJsonObject{put("name",tool.name);put("arguments",args)}),id))
    }
}
