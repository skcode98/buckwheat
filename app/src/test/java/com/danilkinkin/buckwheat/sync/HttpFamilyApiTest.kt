package com.danilkinkin.buckwheat.sync

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpFamilyApiTest {

    private var stub: RoutingHttpStub? = null

    @After
    fun tearDown() {
        stub?.close()
    }

    private fun startApi(routes: Map<String, String>): HttpFamilyApi {
        val started = RoutingHttpStub(routes)
        stub = started
        return HttpFamilyApi("http://127.0.0.1:${started.port}")
    }

    @Test
    fun theEndpointIsTheFamilyActionOnTheBaseUrl() {
        assertEquals("https://sync.example.com/v1/family/create", familyEndpoint("https://sync.example.com", "create"))
        assertEquals("https://sync.example.com/v1/family/create", familyEndpoint("https://sync.example.com/", "create"))
        assertEquals(
            "https://sync.example.com/v1/family/join",
            familyEndpoint("  https://sync.example.com/  ", "join"),
        )
    }

    @Test
    fun credentialsAreDecodedFromTheCreateAndJoinResponse() {
        val credentials = decodeCredentials(
            """{"familyId":"family-1","memberId":"member-1","token":"token-1"}"""
        )

        assertEquals("family-1", credentials.familyId)
        assertEquals("member-1", credentials.memberId)
        assertEquals("token-1", credentials.token)
    }

    @Test
    fun whoamiIsDecoded() {
        val who = decodeWhoAmI(
            """{"memberId":"member-1","familyId":"family-1","displayName":"Suraj"}"""
        )

        assertEquals("member-1", who.memberId)
        assertEquals("family-1", who.familyId)
        assertEquals("Suraj", who.displayName)
    }

    @Test
    fun aJoinCodeIsDecodedFromTheCreateResponse() = runTest {
        val api = startApi(
            mapOf(
                "/v1/family/create" to
                    """{"familyId":"f","memberId":"m","token":"t","joinCode":"ABCD2345"}""",
            ),
        )

        val credentials = api.createFamily("Owner")

        assertEquals("ABCD2345", credentials.joinCode)
    }

    @Test
    fun aDepartedMemberIsDecoded() = runTest {
        val api = startApi(
            mapOf(
                "/v1/family/members" to
                    """{"members":[{"id":"m","displayName":"Me","departed":false,"joinedAt":"x"},{"id":"o","displayName":"Them","departed":true,"joinedAt":"y"}]}""",
            ),
        )

        val members = api.members("token")

        assertEquals(listOf(false, true), members.map { it.departed })
    }

    @Test
    fun aResponseThatIsNotJsonIsAnIoFailure() {
        val failure = runCatching { decodeCredentials("<html>nope</html>") }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("family response was not json", failure?.message)
    }

    @Test
    fun aResponseMissingAFieldIsAFailure() {
        val failure = runCatching { decodeWhoAmI("""{"memberId":"member-1"}""") }.exceptionOrNull()

        assertTrue(failure is Exception)
    }

    @Test
    fun theServerErrorCodeIsSurfacedWhenPresent() {
        assertEquals("owner_only", errorCodeOf("""{"error":"owner_only"}"""))
        assertEquals(null, errorCodeOf("""{"other":"x"}"""))
        assertEquals(null, errorCodeOf("not json at all"))
        assertEquals(null, errorCodeOf(""))
    }
}

private class RoutingHttpStub(private val routes: Map<String, String>) {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val worker = Thread { serve() }

    val port: Int get() = server.localPort

    init {
        worker.isDaemon = true
        worker.start()
    }

    fun close() {
        runCatching { server.close() }
        worker.join(2_000)
    }

    private fun serve() {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return
            handle(socket)
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            val input = it.getInputStream().bufferedReader(Charsets.UTF_8)
            val requestLine = input.readLine() ?: return
            val path = requestLine.split(" ").getOrNull(1).orEmpty()

            var contentLength = 0
            while (true) {
                val header = input.readLine() ?: break
                if (header.isEmpty()) break
                val separator = header.indexOf(':')
                if (separator < 0) continue
                if (header.substring(0, separator).trim().equals("Content-Length", ignoreCase = true)) {
                    contentLength = header.substring(separator + 1).trim().toIntOrNull() ?: 0
                }
            }

            var read = 0
            val body = CharArray(contentLength)
            while (read < contentLength) {
                val count = input.read(body, read, contentLength - read)
                if (count < 0) break
                read += count
            }

            val found = routes[path]
            val bytes = (found ?: """{"error":"not_found"}""").toByteArray(Charsets.UTF_8)
            val status = if (found != null) "200 OK" else "404 Not Found"
            val output = it.getOutputStream()
            output.write(
                (
                    "HTTP/1.1 $status\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"
                    ).toByteArray(Charsets.UTF_8)
            )
            output.write(bytes)
            output.flush()
        }
    }
}
