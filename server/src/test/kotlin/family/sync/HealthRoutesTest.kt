package family.sync

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthRoutesTest {

    @Test
    fun healthReportsOk() = testApplication {
        application { familySyncModule(TestDatabase.dataSource) }
        val response = client.get("/health")
        assertEquals(200, response.status.value)
        assertTrue(response.bodyAsText().contains("\"status\":\"ok\""))
    }

    @Test
    fun healthReportsOkWhenReachedRepeatedly() = testApplication {
        application { familySyncModule(TestDatabase.dataSource) }
        assertEquals(200, client.get("/health").status.value)
    }
}
