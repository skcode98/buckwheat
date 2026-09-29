package family.sync

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun runServer(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    application { familySyncModule(TestDatabase.dataSource) }
    block()
}

suspend fun ApplicationTestBuilder.postJson(
    path: String,
    body: String,
    token: String? = null,
): HttpResponse = client.post(path) {
    contentType(ContentType.Application.Json)
    token?.let { header("Authorization", "Bearer $it") }
    setBody(body)
}

suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

suspend fun HttpResponse.field(name: String): String {
    val value = json()[name]?.jsonPrimitive?.content
    return requireNotNull(value) { "response has no field $name: ${bodyAsText()}" }
}

fun kotlinx.serialization.json.JsonElement?.jsonPrimitiveText(): String? =
    this?.jsonPrimitive?.content

fun kotlinx.serialization.json.JsonElement?.jsonArrayText(): List<String> {
    val array = this?.jsonArray ?: return emptyList()
    return array.map { it.jsonPrimitive.content }
}

fun tamper(token: String): String = token.dropLast(1) + if (token.last() == 'A') 'B' else 'A'
