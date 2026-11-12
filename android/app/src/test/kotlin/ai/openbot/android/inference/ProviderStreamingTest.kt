package ai.openbot.android.inference

import ai.openbot.core.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.Protocol
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import kotlin.test.*

/** Real HTTPS socket/SSE tests with a test-only trust root, never a production trust override. */
class ProviderStreamingTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: ProviderModel
    private lateinit var settings: ProviderSettings
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @BeforeTest fun setup() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer()
        // HTTP/2 has DATA frames: MockWebServer setChunkedBody encodes HTTP/1 chunks.
        server.protocols = listOf(Protocol.HTTP_1_1)
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        val client = OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        provider = ProviderModel(client = client)
        settings = ProviderSettings(baseUrl = server.url("/v1").toString().trimEnd('/'))
    }
    @AfterTest fun teardown() { server.shutdown() }

    @Test fun `Responses text arrives before terminal and stream request carries encrypted history flags`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setChunkedBody(
            ": ping\r\n\r\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"नमस्ते 🌍\"}\r\n\r\n" +
            "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"नमस्ते 🌍\"}]}]}}\n\n", 7))
        val partials = mutableListOf<String>()
        val protocol = AgentProtocol(settings, "Hello", emptyList())
        val answer = provider.stream(settings, "fixture-key", protocol.request()) { partials += it }
        assertEquals(listOf("नमस्ते 🌍"), partials)
        assertEquals("नमस्ते 🌍", protocol.read(answer).text)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/v1/responses", request.path)
        assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
        val body = obj(request.body.readUtf8())
        assertEquals(JsonPrimitive(true), body["stream"])
        assertEquals(JsonPrimitive(false), body["store"])
        assertEquals("reasoning.encrypted_content", body["include"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test fun `EOF after visible text fails instead of pretending the answer completed`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Partial answer\"}\n\n"))
        val partials = mutableListOf<String>()
        assertFailsWith<IllegalStateException> { provider.stream(settings, "fixture-key", providerRequest(settings, "Hello")) { partials += it } }
        assertEquals(listOf("Partial answer"), partials)
    }

    @Test fun `redirect never sends credentials to the redirected endpoint`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/credential-trap")))
        assertFailsWith<IllegalStateException> { provider.stream(settings, "fixture-key", providerRequest(settings, "Hello")) {} }
        assertEquals(1, server.requestCount)
        assertEquals("/v1/responses", server.takeRequest().path)
    }

    @Test fun `JSON-only compatible providers retain the existing response path`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Complete"}]}]}"""))
        val answer = provider.stream(settings, "fixture-key", providerRequest(settings, "Hello")) { fail("No synthetic stream events") }
        assertEquals("Complete", AgentProtocol(settings, "Hello", emptyList()).read(answer).text)
    }

    @Test fun `cancel closes a stalled stream promptly`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"type\":\"response.output_text.delta\",\"delta\":\"First\"}\n\n" + " ".repeat(2048))
            .throttleBody(100, 2, TimeUnit.SECONDS))
        val visible = CompletableDeferred<Unit>()
        val job = launch { provider.stream(settings, "fixture-key", providerRequest(settings, "Hello")) { visible.complete(Unit) } }
        withTimeout(5_000) { visible.await() }
        job.cancel()
        withTimeout(3_000) { job.join() }
        assertTrue(job.isCancelled)
    }
}
