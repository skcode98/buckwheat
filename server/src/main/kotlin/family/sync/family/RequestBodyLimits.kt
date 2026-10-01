package family.sync.family

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.utils.io.jvm.javaio.copyTo
import java.io.ByteArrayOutputStream

private class BodyLimitConfig {
    var maxBytes: Long = DEFAULT_MAX_REQUEST_BYTES
}

fun Application.installRequestBodyLimit(maxRequestBytes: Long) {
    val plugin = createApplicationPlugin(
        name = "RequestBodyLimit",
        createConfiguration = ::BodyLimitConfig,
    ) {
        val maxBytes = pluginConfig.maxBytes
        onCall { call -> call.rejectOversizedDeclaredBody(maxBytes) }
    }
    install(plugin) { this.maxBytes = maxRequestBytes }
}

suspend fun ApplicationCall.receiveTextLimited(maxBytes: Long): String {
    rejectOversizedDeclaredBody(maxBytes)
    val sink = ByteArrayOutputStream()
    val copied = request.receiveChannel().copyTo(sink, maxBytes + 1)
    if (copied > maxBytes) throw PayloadTooLargeException("payload_too_large")
    return String(sink.toByteArray(), Charsets.UTF_8)
}

private fun ApplicationCall.rejectOversizedDeclaredBody(maxBytes: Long) {
    val declared = request.headers[HttpHeaders.ContentLength]?.trim()?.toLongOrNull()
    if (declared != null && declared > maxBytes) {
        throw PayloadTooLargeException("payload_too_large")
    }
}
