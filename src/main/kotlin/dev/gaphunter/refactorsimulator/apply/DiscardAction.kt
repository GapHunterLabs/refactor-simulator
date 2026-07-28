package dev.gaphunter.refactorsimulator.apply

import dev.gaphunter.refactorsimulator.sandbox.SandboxSession
import dev.gaphunter.refactorsimulator.testimpact.IsolatedTestRunner

/**
 * Formal no-op: releases the sandbox and any temp dir IsolatedTestRunner
 * may have created. Exists as its own action (rather than relying on
 * garbage collection) so "zero footprint" is auditable/testable, not
 * just an assumption.
 */
object DiscardAction {

    fun discard(session: SandboxSession, testRunner: IsolatedTestRunner?) {
        testRunner?.disposeSessionTempDir()
        session.dispose()
    }
}
