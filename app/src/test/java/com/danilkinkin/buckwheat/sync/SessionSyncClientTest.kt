package com.danilkinkin.buckwheat.sync

import com.danilkinkin.buckwheat.di.FakeSessionStore
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordedRequest(
    val method: String,
    val path: String,
    val authorization: String,
    val body: String,
)

private class LoopbackHttpStub(
    private val responseBody: String = """{"cursor":9,"accepted":[],"records":[],"conflicts":[]}""",
) {
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    private val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
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
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0).orEmpty()
            val path = parts.getOrNull(1).orEmpty()

            var contentLength = 0
            var authorization = ""
            while (true) {
                val header = input.readLine() ?: break
                if (header.isEmpty()) break
                val separator = header.indexOf(':')
                if (separator < 0) continue
                val name = header.substring(0, separator).trim()
                val value = header.substring(separator + 1).trim()
                if (name.equals("Content-Length", ignoreCase = true)) {
                    contentLength = value.toIntOrNull() ?: 0
                }
                if (name.equals("Authorization", ignoreCase = true)) {
                    authorization = value
                }
            }

            val body = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val count = input.read(body, read, contentLength - read)
                if (count < 0) break
                read += count
            }

            requests.add(RecordedRequest(method, path, authorization, String(body, 0, read)))

            val bytes = responseBody.toByteArray(Charsets.UTF_8)
            val output = it.getOutputStream()
            output.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
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

class SessionSyncClientTest {

    private val store = FakeSessionStore()
    private var stub: LoopbackHttpStub? = null

    @After
    fun tearDown() {
        stub?.close()
    }

    private fun startStub(): String {
        val started = LoopbackHttpStub()
        stub = started
        return "http://127.0.0.1:${started.port}"
    }

    private fun recorded(): List<RecordedRequest> = stub?.requests.orEmpty()

    private suspend fun storeSession(baseUrl: String) =
        store.save(baseUrl, "token-9", "family-1", "member-1")

    @Test
    fun withoutASessionTheSyncFails() = runTest {
        val thrown = runCatching {
            SessionSyncClient(store).sync("token-1", SyncRequest(0, emptyList()))
        }.exceptionOrNull()

        assertTrue(thrown is IOException)
        assertEquals("no family session", thrown?.message)
    }

    @Test
    fun aSyncWithoutASessionNeverTouchesTheNetwork() = runTest {
        startStub()

        runCatching { SessionSyncClient(store).sync("token-1", SyncRequest(0, emptyList())) }

        assertEquals(emptyList<RecordedRequest>(), recorded())
    }

    @Test
    fun aSyncedSessionPostsToTheSyncPathOnTheSessionBaseUrl() = runTest {
        val baseUrl = startStub()
        storeSession(baseUrl)

        val response = SessionSyncClient(store).sync(
            token = "token-9",
            request = SyncRequest(4, emptyList()),
        )

        assertEquals(9L, response.cursor)
        val request = recorded().single()
        assertEquals("POST", request.method)
        assertEquals("/v1/sync", request.path)
        assertEquals("Bearer token-9", request.authorization)
        assertTrue(request.body.contains("\"cursor\":4"))
    }

    @Test
    fun theBaseUrlIsReadOnEveryCall() = runTest {
        val baseUrl = startStub()
        storeSession(baseUrl)
        val client = SessionSyncClient(store)

        client.sync("token-9", SyncRequest(0, emptyList()))
        store.clear()
        runCatching { client.sync("token-9", SyncRequest(0, emptyList())) }
        storeSession(baseUrl)
        client.sync("token-9", SyncRequest(0, emptyList()))

        assertEquals(2, recorded().size)
    }

    @Test
    fun trailingSlashesInTheStoredBaseUrlDoNotDoubleThePath() = runTest {
        val baseUrl = startStub()
        storeSession("$baseUrl/")

        SessionSyncClient(store).sync("token-9", SyncRequest(0, emptyList()))

        assertEquals("/v1/sync", recorded().single().path)
    }

    @Test
    fun changesAreSerialisedIntoTheRequestBody() = runTest {
        val baseUrl = startStub()
        storeSession(baseUrl)

        SessionSyncClient(store).sync(
            token = "token-9",
            request = SyncRequest(
                cursor = 2,
                changes = listOf(
                    LocalRecord(
                        table = SyncTables.TRANSACTIONS,
                        id = "tx-1",
                        updatedAt = 11,
                        version = 3,
                        deletedAt = null,
                        payload = """{"type":"SPENT"}""",
                        dirty = true,
                        memberId = "member-1",
                    )
                ),
            ),
        )

        val body = recorded().single().body
        assertTrue(body.contains("\"cursor\":2"))
        assertTrue(body.contains("\"table\":\"transactions\""))
        assertTrue(body.contains("\"id\":\"tx-1\""))
        assertTrue(body.contains("\"version\":3"))
        assertTrue(body.contains("\"type\":\"SPENT\""))
    }
}
