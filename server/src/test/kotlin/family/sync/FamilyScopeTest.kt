package family.sync

import family.sync.auth.TokenService
import family.sync.family.SecuritySettings
import java.security.MessageDigest
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FamilyScopeTest {

    @BeforeTest
    fun cleanDatabase() {
        TestDatabase.truncateAll()
    }

    @Test
    fun aValidTokenResolvesToItsMemberAndFamily() {
        val service = tokenService()
        val family = TestDatabase.createFamily("parent")
        val token = service.mint(family.memberId, family.familyId)

        val principal = assertNotNull(service.verify(token))

        assertEquals(family.memberId, principal.memberId)
        assertEquals(family.familyId, principal.familyId)
    }

    @Test
    fun aTamperedTokenIsRefused() {
        val service = tokenService()
        val family = TestDatabase.createFamily("parent")
        val token = service.mint(family.memberId, family.familyId)
        val tampered = token.dropLast(1) + if (token.last() == 'A') 'B' else 'A'

        assertNull(service.verify(tampered))
    }

    @Test
    fun anUnknownTokenIsRefused() {
        val service = tokenService()

        assertNull(service.verify("this-was-never-issued"))
    }

    @Test
    fun anEmptyTokenIsRefused() {
        val service = tokenService()

        assertNull(service.verify(""))
    }

    @Test
    fun aTokenIsNeverStoredInPlainText() {
        val service = tokenService()
        val family = TestDatabase.createFamily("parent")
        val token = service.mint(family.memberId, family.familyId)

        val stored = TestDatabase.readTokenHashes()
        val expectedHash = sha256Hex(token)

        assertTrue(stored.none { it == token }, "the raw token must never be stored")
        assertTrue(stored.contains(expectedHash), "the sha256 of the token must be stored")
    }

    @Test
    fun twoTokensForTheSameMemberAreBothValidAndDistinct() {
        val service = tokenService()
        val family = TestDatabase.createFamily("parent")

        val first = service.mint(family.memberId, family.familyId)
        val second = service.mint(family.memberId, family.familyId)

        assertTrue(first != second, "each mint must produce a distinct token")
        assertEquals(family.memberId, assertNotNull(service.verify(first)).memberId)
        assertEquals(family.memberId, assertNotNull(service.verify(second)).memberId)
    }

    @Test
    fun aTokenFromOneFamilyCannotResolveAnotherFamily() {
        val service = tokenService()
        val first = TestDatabase.createFamily("first")
        val second = TestDatabase.createFamily("second")

        val firstPrincipal = assertNotNull(
            service.verify(service.mint(first.memberId, first.familyId))
        )
        val secondPrincipal = assertNotNull(
            service.verify(service.mint(second.memberId, second.familyId))
        )

        assertTrue(firstPrincipal.familyId != secondPrincipal.familyId)
        assertTrue(firstPrincipal.memberId != secondPrincipal.memberId)
    }

    @Test
    fun revokingATokenStopsItResolving() {
        val service = tokenService()
        val family = TestDatabase.createFamily("parent")
        val token = service.mint(family.memberId, family.familyId)
        assertNotNull(service.verify(token))

        service.revoke(token)

        assertNull(service.verify(token))
    }

    @Test
    fun revokingOneTokenLeavesTheOtherValid() {
        val service = tokenService()
        val family = TestDatabase.createFamily("parent")
        val revoked = service.mint(family.memberId, family.familyId)
        val kept = service.mint(family.memberId, family.familyId)

        service.revoke(revoked)

        assertNull(service.verify(revoked))
        assertNotNull(service.verify(kept))
    }

    @Test
    fun revokingATokenThatWasNeverIssuedIsHarmless() {
        val service = tokenService()

        service.revoke("never-issued")
    }

    @Test
    fun aTokenThatOutlivedItsLifetimeIsRefused() {
        val family = TestDatabase.createFamily("parent")
        val token = tokenService().mint(family.memberId, family.familyId)

        val expired = TokenService(TestDatabase.dataSource, Duration.ZERO)

        assertNull(expired.verify(token))
    }

    @Test
    fun aTokenInsideItsLifetimeStillResolves() {
        val family = TestDatabase.createFamily("parent")
        val token = tokenService().mint(family.memberId, family.familyId)

        val principal = assertNotNull(
            TokenService(TestDatabase.dataSource, Duration.ofDays(1)).verify(token)
        )

        assertEquals(family.memberId, principal.memberId)
    }

    @Test
    fun theLifetimeIsOptInAndReadFromTheEnvironment() {
        // The lifetime is a window on created_at, so it applies retroactively. A finite default
        // would invalidate every token already in the field the moment it deployed.
        assertEquals(Duration.ofDays(3650), SecuritySettings.fromEnv(emptyMap()).tokenLifetime)
        assertEquals(
            Duration.ofDays(3650),
            SecuritySettings.fromEnv(mapOf("TOKEN_LIFETIME_DAYS" to "junk")).tokenLifetime,
        )
        assertEquals(
            Duration.ofDays(7),
            SecuritySettings.fromEnv(mapOf("TOKEN_LIFETIME_DAYS" to "7")).tokenLifetime,
        )
        assertEquals(
            Duration.ofDays(3650),
            SecuritySettings.fromEnv(mapOf("TOKEN_LIFETIME_DAYS" to "-1")).tokenLifetime,
        )
    }

    private fun tokenService(): TokenService = TokenService(TestDatabase.dataSource)

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
