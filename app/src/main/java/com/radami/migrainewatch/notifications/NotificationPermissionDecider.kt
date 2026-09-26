package com.radami.migrainewatch.notifications

/**
 * Turns the two system flags and our own record of having asked into the state the UI acts on.
 * Kept free of Android types so the rule can be read and tested on its own.
 */
object NotificationPermissionDecider {

    /**
     * @param permissionGranted POST_NOTIFICATIONS held; always true below Android 13.
     * @param notificationsAllowed notifications not switched off for the app as a whole.
     * @param alreadyAsked the runtime dialog has been shown once before.
     */
    fun decide(
        permissionGranted: Boolean,
        notificationsAllowed: Boolean,
        alreadyAsked: Boolean
    ): NotificationPermissionState = when {
        permissionGranted && notificationsAllowed -> NotificationPermissionState.GRANTED

        // Checked before notificationsAllowed: a fresh Android 13 install reports
        // notifications as disabled purely for lack of the permission, not because the user
        // switched them off.
        !permissionGranted && !alreadyAsked -> NotificationPermissionState.REQUESTABLE

        // Either notifications are switched off for the app, or the dialog was already shown
        // and Android silently rejects further requests.
        else -> NotificationPermissionState.BLOCKED
    }
}
