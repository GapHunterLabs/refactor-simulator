package dev.gaphunter.refactorsimulator.review

import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader

/**
 * Asks the user to rate the plugin on Marketplace, once, after they've
 * used a real number of successful "Apply to Disk" actions -- never on
 * install, never on a timer, and never for just running Simulate or
 * opening the tool window (previewing a refactor is not the same
 * signal of value as actually trusting the result enough to apply it).
 * Every call to [recordHit] here already corresponds to one real
 * refactor applied to disk. Counts invocations directly instead of
 * tracking a set of seen keys.
 *
 * Threshold is deliberately lower than the detector-based plugins'
 * (5, not 10) -- an explicit action invocation is a much stronger
 * signal of real value than a passive finding the plugin noticed on
 * its own.
 *
 * Same "earn the ask" principle as other well-regarded plugins; never
 * re-asks once the user has either rated or dismissed it.
 *
 * Persisted via [PropertiesComponent] at the application level (not
 * per-project) -- how many times this plugin has been used isn't tied
 * to any one project, and neither is whether the user already
 * answered.
 */
object ReviewPrompt {

    /** How many successful "Apply to Disk" actions before the prompt shows once. */
    private const val HITS_BEFORE_PROMPT = 5

    private const val KEY_HIT_COUNT = "dev.gaphunter.refactorsimulator.review.hitCount"
    private const val KEY_ANSWERED = "dev.gaphunter.refactorsimulator.review.answered"

    private const val NOTIFICATION_GROUP_ID = "Refactor Simulator"

    // TODO(post-first-publish): Marketplace only assigns a numeric plugin
    // ID on the first manual submit (queued, see demo/README.md) -- until
    // then this points at the vendor page so "Rate on Marketplace" still
    // goes somewhere real instead of a 404. Update to
    // https://plugins.jetbrains.com/plugin/<id>-__SLUG__/reviews once the
    // real ID is known (recorded in the same place as the other
    // post-publish follow-ups).
    private const val MARKETPLACE_URL = "https://plugins.jetbrains.com/vendor/gap-hunter-labs"

    /**
     * Call this once per real, successful "Apply to Disk" action. Safe
     * to call on any thread.
     */
    fun recordHit(project: Project?) {
        val properties = PropertiesComponent.getInstance()
        if (properties.getBoolean(KEY_ANSWERED)) return

        val count = properties.getInt(KEY_HIT_COUNT, 0) + 1
        properties.setValue(KEY_HIT_COUNT, count, 0)

        if (count == HITS_BEFORE_PROMPT) {
            showPrompt(project)
        }
    }

    private fun showPrompt(project: Project?) {
        val properties = PropertiesComponent.getInstance()

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(
                "Refactor Simulator",
                "You've used this $HITS_BEFORE_PROMPT times -- if it's saved you time, a rating on the Marketplace helps other developers find it.",
                NotificationType.INFORMATION,
            )
        notification.setIcon(IconLoader.getIcon("/META-INF/pluginIcon.svg", ReviewPrompt::class.java))

        notification.addAction(NotificationAction.createSimpleExpiring("Rate on Marketplace") {
            properties.setValue(KEY_ANSWERED, true)
            BrowserUtil.browse(MARKETPLACE_URL)
        })
        notification.addAction(NotificationAction.createSimpleExpiring("Don't ask again") {
            properties.setValue(KEY_ANSWERED, true)
        })

        notification.notify(project)
    }
}
