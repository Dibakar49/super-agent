package ai.openbot.core

import kotlinx.serialization.json.*
import kotlin.test.*

class McpTest {
    @Test fun `JSON and event stream replies must match the request identity`() {
        val body="""{"jsonrpc":"2.0","id":"r1","result":{"tools":[]}}"""
        assertEquals(0,readMcpReply(body,"application/json; charset=utf-8","r1")["tools"]!!.jsonArray.size)
        val stream=": keepalive\r\nevent: message\r\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\r\n\r\nevent: message\r\ndata: $body\r\n\r\n"
        assertEquals(readMcpReply(body,"application/json","r1"),readMcpReply(stream,"text/event-stream","r1"))
        assertFails{readMcpReply(body,"application/json","different")}
        assertFails{readMcpReply(body,"text/html","r1")}
        assertFails{readMcpReply("""{"jsonrpc":"2.0","id":"r1","error":{"message":"Denied","code":-32000}}""","application/json","r1")}
    }
    @Test fun `connection endpoints and model tool names cannot smuggle credentials or collide`() {
        assertEquals("https://example.com/mcp",mcpEndpoint("https://example.com/mcp").toString())
        listOf("http://example.com/mcp","https://key@example.com/mcp","https://example.com/mcp?token=key","https://example.com/mcp#part").forEach{assertFails{mcpEndpoint(it)}}
        val name=mcpModelToolName("a","send/mail")
        assertTrue(Regex("^[a-zA-Z0-9_-]{1,64}$").matches(name))
        assertNotEquals(name,mcpModelToolName("b","send/mail"))
        assertNotEquals(name,mcpModelToolName("a","send_mail"))
        assertEquals(name,mcpModelToolName("a","send/mail"))
    }
    @Test fun `an SSE result returns without waiting for the stream to close and oversized input is refused`() {
        val bytes="data: {\"jsonrpc\":\"2.0\",\"id\":\"r\",\"result\":{\"ok\":true}}\n\n".toByteArray()
        val openStream=object:java.io.InputStream() {
            var position=0
            override fun read():Int {if(position>=bytes.size)error("Tried to wait for the stream to close");return bytes[position++].toInt() and 255}
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                if(position>=bytes.size)error("Tried to wait for the stream to close")
                val size=minOf(length,bytes.size-position);bytes.copyInto(buffer,offset,position,position+size);position+=size;return size
            }
        }
        assertEquals(true,readMcpStream(openStream,"text/event-stream","r")["ok"]!!.jsonPrimitive.boolean)
        assertFails{readMcpStream(java.io.ByteArrayInputStream("x".repeat(1000).toByteArray()),"application/json","r",maximumBytes=100)}
    }

}
