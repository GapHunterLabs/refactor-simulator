package dev.gaphunter.refactorsimulator.licensing

/**
 * Features that moved from the free tier to Pro: a license, or an installation that had them
 * before the move (see [LegacyFreeFeatures]). Fail-closed: only an explicit `true` from the
 * license check counts. Both checks are injectable for tests.
 */
object ProGate {
    @Volatile
    var licenseCheck: () -> Boolean? = { RefactorSimulatorLicense.isLicensed() }

    @Volatile
    var legacyCheck: () -> Boolean = { LegacyFreeFeatures.keepsMovedFeatures() }

    /** Simulating Extract Variable. */
    fun allowsMovedFeatures(): Boolean = licenseCheck() == true || legacyCheck()
}
