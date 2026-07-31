package com.radami.migrainewatch.notifications

/**
 * Turns the two system flags and our own record of having asked into the state the UI acts on.
 * Kept free of Android types so the rule can be read — and tested — on its own.
 */
object NotificationPermissionDecider {

    /**
     * @param permissionGranted POST_NOTIFICATIONS held; always true below Android 13, where it
     *   is granted at install time.
     * @param notificationsAllowed notifications not switched off for the app as a whole.
     * @param alreadyAsked the runtime dialog has been shown once before.
     */
    fun decide(
        permissionGranted: Boolean,
        notificationsAllowed: Boolean,
        alreadyAsked: Boolean
    ): NotificationPermissionState = when {
        permissionGranted && notificationsAllowed -> NotificationPermissionState.GRANTED

        // Switched off for the app: a runtime request cannot undo that, whatever the
        // permission says, so the system settings screen is the only way back.
        !notificationsAllowed -> NotificationPermissionState.BLOCKED

        // Android shows the dialog once. After a denial further requests are auto-rejected
        // with no UI at all, so offering "Allow" again would do visibly nothing.
        alreadyAsked -> NotificationPermissionState.BLOCKED

        else -> NotificationPermissionState.REQUESTABLE
    }
}
