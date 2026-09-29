package family.sync

import family.sync.auth.TokenService
import java.security.MessageDigest
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

    private fun tokenService(): TokenService = TokenService(TestDatabase.dataSource)

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
