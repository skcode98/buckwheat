package family.sync.db

import java.sql.PreparedStatement
import java.sql.Types

fun PreparedStatement.setUuid(index: Int, value: String): PreparedStatement = apply {
    setObject(index, value, Types.OTHER)
}
