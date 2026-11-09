package com.radami.migrainewatch.notifications

/**
 * Whether the system will actually deliver an alert, and what the user can do when it won't.
 * Distinct from the alerts preference, which records only that the user wants them.
 */
enum class NotificationPermissionState {
    GRANTED,

    /** Never asked, so the runtime dialog will still show. */
    REQUESTABLE,

    /** Nothing in-app can fix it; only the system settings screen can. */
    BLOCKED
}
