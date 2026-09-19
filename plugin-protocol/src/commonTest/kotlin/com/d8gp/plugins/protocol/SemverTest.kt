package com.d8gp.plugins.protocol

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

// Port of the semver block in tests/unit/pluginManifest.test.ts.
class SemverTest {
    @Test
    fun `validates x_y_z form`() {
        assertTrue(Semver.isSemver("1.2.3"))
        assertFalse(Semver.isSemver("1.2"))
        assertFalse(Semver.isSemver("1.2.3-beta"))
        assertFalse(Semver.isSemver("v1.2.3"))
        assertTrue(Semver.isSemver("01.2.3")) // \d+ allows leading zeros, like the TS regex
    }

    @Test
    fun `orders versions numerically per part`() {
        assertEquals(-1, Semver.compareSemver("1.0.0", "1.0.1"))
        assertEquals(1, Semver.compareSemver("1.2.0", "1.1.9"))
        assertEquals(0, Semver.compareSemver("2.0.0", "2.0.0"))
        assertEquals(1, Semver.compareSemver("10.0.0", "9.9.9"))
        assertEquals(0, Semver.compareSemver("01.0.0", "1.0.0"))
        assertEquals(1, Semver.compareSemver("1.0.99999999999999999999", "1.0.9999999999999999999"))
        assertEquals(-1, Semver.compareSemver("0.0.0", "0.0.1"))
    }

    @Test
    fun `satisfiesMin is have gte min`() {
        assertTrue(Semver.satisfiesMin("1.2.3", "1.2.3"))
        assertTrue(Semver.satisfiesMin("1.3.0", "1.2.9"))
        assertFalse(Semver.satisfiesMin("1.2.2", "1.2.3"))
    }
}
