package family.sync.sync

import family.sync.family.BadRequestException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

// Mirrors the Android TransactionType enum; the client falls back to SPENT for anything it does not
// recognise, so a row carrying an unknown type is corruption rather than a new business value.
val TRANSACTION_TYPES = setOf("SET_DAILY_BUDGET", "INCOME", "SPENT")

// TEXT columns are free-form (a transaction comment can be a whole paragraph) but they are synced to
// every member of the family, so unbounded input turns one client into a fan-out of stored garbage.
// Measured in characters, not bytes.
const val MAX_TEXT_LENGTH = 4096

// PostgreSQL numeric holds up to 131072 integer digits. Money never needs that, so anything past
// these bounds is rejected as payload_invalid instead of overflowing the column at insert time.
const val MAX_NUMERIC_INTEGER_DIGITS = 15
const val MAX_NUMERIC_SCALE = 6
const val MAX_NUMERIC_LENGTH = MAX_NUMERIC_INTEGER_DIGITS + MAX_NUMERIC_SCALE + 2

fun readPayload(raw: String, spec: TableSpec): List<String?> {
    val parsed = try {
        Json.parseToJsonElement(raw)
    } catch (failure: SerializationException) {
        throw BadRequestException("payload_invalid")
    } as? JsonObject ?: throw BadRequestException("payload_invalid")
    return spec.columns.map { column ->
        val element = parsed[column.key]
        if (element == null || element is JsonNull) {
            if (column.nullable) return@map null
            throw BadRequestException("payload_incomplete")
        }
        val primitive = element as? JsonPrimitive ?: throw BadRequestException("payload_invalid")
        requireValidValue(column, primitive)
        primitive.content
    }
}

fun requireValidValue(column: PayloadColumn, primitive: JsonPrimitive) {
    val text = primitive.content
    if (column.allowedValues != null && text !in column.allowedValues) {
        throw BadRequestException("payload_invalid")
    }
    val valid = when (column.type) {
        SqlType.TEXT -> text.length <= MAX_TEXT_LENGTH
        SqlType.BOOLEAN -> if (primitive.isString) {
            text.equals("true", ignoreCase = true) || text.equals("false", ignoreCase = true)
        } else {
            text == "true" || text == "false"
        }

        SqlType.INTEGER -> text.toIntOrNull() != null
        SqlType.BIGINT -> text.toLongOrNull() != null
        SqlType.NUMERIC -> isValidNumeric(text)

        // The placeholder casts through uuid, so anything Postgres would reject has to fail here
        // as payload_invalid rather than surfacing as a 500 from the driver. `UUID.fromString`
        // is deliberately lenient about group widths, so it accepts "0-0-0-0-0" and Postgres
        // rejects that; re-rendering the parsed value is what forces the canonical 8-4-4-4-12 form.
        SqlType.UUID -> runCatching { UUID.fromString(text) }
            .map { it.toString().equals(text, ignoreCase = true) }
            .getOrDefault(false)
    }
    if (!valid) throw BadRequestException("payload_invalid")
}

private fun isValidNumeric(text: String): Boolean {
    if (text.isEmpty() || text.length > MAX_NUMERIC_LENGTH) return false
    val value = text.toBigDecimalOrNull() ?: return false
    if (value.scale() > MAX_NUMERIC_SCALE) return false
    // A negative scale means trailing zeros in scientific notation ("1E+3" holds four digits), so
    // subtracting it counts the real integer width.
    return value.precision() - value.scale() <= MAX_NUMERIC_INTEGER_DIGITS
}