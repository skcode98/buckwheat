package com.danilkinkin.buckwheat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHashTest {
    @Test
    fun hashIsDeterministicForSamePin() {
        val h1 = generatePinHash("1234")
        val h2 = generatePinHash("1234")
        // Different salts, so hashes differ, but both verify.
        assertNotEquals(h1, h2)
        assertTrue(verifyPinHash("1234", h1))
        assertTrue(verifyPinHash("1234", h2))
    }

    @Test
    fun differentPinsProduceDifferentHashes() {
        val h = generatePinHash("1234")
        assertFalse(verifyPinHash("1235", h))
        assertFalse(verifyPinHash("0000", h))
    }

    @Test
    fun hashHasVersionedFormat() {
        val parts = generatePinHash("12345678").split('$')
        assertEquals(3, parts.size)
        assertEquals("v1", parts[0])
        assertEquals(32, parts[1].length)
        assertEquals(64, parts[2].length)
    }

    @Test
    fun hashNeverContainsPlainPin() {
        assert(generatePinHash("1234").contains("1234").not())
    }

    @Test
    fun legacySha256HashStillVerifies() {
        // The exact legacy output for "1234" under the static salt, as written before the
        // PBKDF2 migration. Existing users' stored hashes must keep working.
        val legacy = "abb615b85a47b26b2e19dcf984c4ecac2ccd3c3046c835a4b67204864f32745c"
        assertTrue(verifyPinHash("1234", legacy))
        assertFalse(verifyPinHash("1235", legacy))
    }

    @Test
    fun legacyHashesAreDetectedForMigration() {
        assertTrue(isLegacyPinHash("abb615b85a47b26b2e19dcf984c4ecac2ccd3c3046c835a4b67204864f32745c"))
        assertFalse(isLegacyPinHash(generatePinHash("1234")))
        assertFalse(isLegacyPinHash(null))
        assertFalse(isLegacyPinHash(""))
    }

    @Test
    fun verifyRejectsGarbage() {
        assertFalse(verifyPinHash("1234", null))
        assertFalse(verifyPinHash("1234", ""))
        assertFalse(verifyPinHash("1234", "not-a-hash"))
        assertFalse(verifyPinHash("1234", "v1\$zz\$yy"))
        assertFalse(verifyPinHash("1234", "v1\$0102\$zz"))
    }
}
