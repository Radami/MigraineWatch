package com.radami.migrainewatch.notifications

import kotlin.time.Instant

/** A notification the system shows by itself at [fireAt], without the app running. */
data class LocalNotification(
    val identifier: String,
    val title: String,
    val body: String,
    val fireAt: Instant
)

/**
 * The platform's scheduled local notifications: the user notification center on iOS. Behind an
 * interface so the rules for what gets scheduled can be tested without a device.
 */
interface LocalNotificationCenter {

    /** Identifiers of notifications scheduled and not yet shown. */
    suspend fun pendingIdentifiers(): Set<String>

    /**
     * Schedules [notification], replacing one already pending under its identifier.
     *
     * @return whether the system took it; false when the app may not post notifications.
     */
    suspend fun schedule(notification: LocalNotification): Boolean

    suspend fun cancel(identifier: String)
}
