package dev.gaphunter.refactorsimulator.licensing

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProGateTest {
    private lateinit var originalLicense: () -> Boolean?
    private lateinit var originalLegacy: () -> Boolean

    @Before
    fun remember() {
        originalLicense = ProGate.licenseCheck
        originalLegacy = ProGate.legacyCheck
    }

    @After
    fun restore() {
        ProGate.licenseCheck = originalLicense
        ProGate.legacyCheck = originalLegacy
    }

    @Test
    fun `Extract Variable needs a license on a new installation`() {
        ProGate.licenseCheck = { false }
        ProGate.legacyCheck = { false }
        assertFalse(ProGate.allowsMovedFeatures())
        ProGate.licenseCheck = { null }
        assertFalse(ProGate.allowsMovedFeatures())
    }

    @Test
    fun `Extract Variable stays available with a license or on an installation that had it`() {
        ProGate.licenseCheck = { true }
        ProGate.legacyCheck = { false }
        assertTrue(ProGate.allowsMovedFeatures())
        ProGate.licenseCheck = { false }
        ProGate.legacyCheck = { true }
        assertTrue(ProGate.allowsMovedFeatures())
    }

    @Test
    fun `the legacy stamp only counts before the cutoff`() {
        assertTrue(LegacyFreeFeatures.keeps(LegacyFreeFeatures.CUTOFF.minusDays(1).toString()))
        assertFalse(LegacyFreeFeatures.keeps(LegacyFreeFeatures.CUTOFF.toString()))
        assertFalse(LegacyFreeFeatures.keeps(null))
        assertFalse(LegacyFreeFeatures.keeps("not a date"))
    }
}
