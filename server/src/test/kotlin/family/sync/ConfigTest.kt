package family.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ConfigTest {

    private fun postgresUri(): String =
        "postgresql://" + "user" + ":" + "pw" + "123456" + "@db.example.com:5432/family_sync"

    @Test
    fun aPostgresUriIsConvertedToAJdbcUrl() {
        val config = loadConfig(mapOf("DATABASE_URL" to postgresUri()))

        assertEquals(
            "jdbc:postgresql://db.example.com:5432/family_sync?sslmode=require",
            config.databaseUrl,
        )
    }

    @Test
    fun credentialsAreDroppedFromTheUrlBecauseHikariSuppliesThem() {
        val config = loadConfig(
            mapOf(
                "DATABASE_URL" to postgresUri(),
                "DATABASE_USER" to "user",
                "DATABASE_PASSWORD" to "pw123456",
            )
        )

        assertEquals("user", config.databaseUser)
        assertEquals("pw123456", config.databasePassword)
        assertFalse(config.databaseUrl.contains("pw123456"))
    }

    @Test
    fun theSslModeDefaultsToRequire() {
        val config = loadConfig(mapOf("DATABASE_URL" to postgresUri()))

        assertEquals(true, config.databaseUrl.endsWith("sslmode=require"))
    }

    @Test
    fun theSslModeCanBeDisabledForLocalDatabases() {
        val config = loadConfig(
            mapOf("DATABASE_URL" to postgresUri(), "DATABASE_SSL_MODE" to "disable")
        )

        assertEquals(
            "jdbc:postgresql://db.example.com:5432/family_sync?sslmode=disable",
            config.databaseUrl,
        )
    }

    @Test
    fun anExistingSslModeInTheUrlIsNotOverwritten() {
        val config = loadConfig(
            mapOf(
                "DATABASE_URL" to "jdbc:postgresql://localhost:5432/db?sslmode=verify-full",
                "DATABASE_SSL_MODE" to "disable",
            )
        )

        assertEquals("jdbc:postgresql://localhost:5432/db?sslmode=verify-full", config.databaseUrl)
    }

    @Test
    fun aSupabaseUriKeepsExactlyOneSslMode() {
        val config = loadConfig(
            mapOf(
                "DATABASE_URL" to "postgresql://postgres.abcdefghijklm:secret@" +
                    "aws-0-ap-south-1.pooler.supabase.com:5432/postgres?sslmode=require",
            )
        )

        assertEquals(
            "jdbc:postgresql://aws-0-ap-south-1.pooler.supabase.com:5432/postgres?sslmode=require",
            config.databaseUrl,
        )
        assertEquals(1, config.databaseUrl.split("sslmode=").size - 1)
    }

    @Test
    fun aSupabaseUriHonoursTheConfiguredSslMode() {
        val config = loadConfig(
            mapOf(
                "DATABASE_URL" to "postgresql://u:p@db.example.com:5432/postgres?sslmode=require",
                "DATABASE_SSL_MODE" to "verify-full",
            )
        )

        assertEquals(
            "jdbc:postgresql://db.example.com:5432/postgres?sslmode=verify-full",
            config.databaseUrl,
        )
    }

    @Test
    fun unrelatedQueryParametersSurvive() {
        val config = loadConfig(
            mapOf(
                "DATABASE_URL" to "postgresql://db.example.com:5432/db" +
                    "?application_name=buckwheat&sslmode=require",
            )
        )

        assertEquals(
            "jdbc:postgresql://db.example.com:5432/db?application_name=buckwheat&sslmode=require",
            config.databaseUrl,
        )
    }

    @Test
    fun anUppercaseSslModeInTheUriIsReplaced() {
        val config = loadConfig(
            mapOf("DATABASE_URL" to "postgresql://db.example.com:5432/db?SSLMode=disable")
        )

        assertEquals("jdbc:postgresql://db.example.com:5432/db?sslmode=require", config.databaseUrl)
    }

    @Test
    fun aUriWithoutAPortKeepsThePortOff() {
        val config = loadConfig(mapOf("DATABASE_URL" to "postgresql://db.example.com/family_sync"))

        assertEquals(
            "jdbc:postgresql://db.example.com/family_sync?sslmode=require",
            config.databaseUrl,
        )
    }

    @Test
    fun anExistingJdbcUrlKeepsItsScheme() {
        val config = loadConfig(mapOf("DATABASE_URL" to "jdbc:postgresql://localhost:5432/db"))

        assertEquals("jdbc:postgresql://localhost:5432/db?sslmode=require", config.databaseUrl)
    }

    @Test
    fun aMissingDatabaseUrlIsRefused() {
        assertFailsWith<IllegalStateException> { loadConfig(emptyMap()) }
    }

    @Test
    fun aUrlWithoutASchemeIsRefused() {
        assertFailsWith<IllegalStateException> {
            loadConfig(mapOf("DATABASE_URL" to "db.example.com:5432/family_sync"))
        }
    }

    @Test
    fun thePortDefaultsToEightThousandEighty() {
        assertEquals(8080, loadConfig(mapOf("DATABASE_URL" to "postgresql://h/db")).port)
    }

    @Test
    fun theServerPortIsReadFromTheEnvironment() {
        val config = loadConfig(mapOf("DATABASE_URL" to "postgresql://h/db", "PORT" to "9000"))

        assertEquals(9000, config.port)
    }

    @Test
    fun theUserDefaultsToPostgres() {
        assertEquals("postgres", loadConfig(mapOf("DATABASE_URL" to "postgresql://h/db")).databaseUser)
    }
}
