package com.radami.migrainewatch.notifications

/**
 * Turns the two system flags and our own record of having asked into the state the UI acts on.
 * Kept free of Android types so the rule can be read — and tested — on its own.
 */
object NotificationPermissionDecider {

    /**
     * @param permissionGranted POST_NOTIFICATIONS held; always true below Android 13, where it
     *   is granted at install time.
     * @param notificationsAllowed notifications not switched off for the app as a whole. From
     *   Android 13 this is false whenever [permissionGranted] is false, so on its own it cannot
     *   tell a user who switched alerts off from one who has simply never been asked.
     * @param alreadyAsked the runtime dialog has been shown once before.
     */
    fun decide(
        permissionGranted: Boolean,
        notificationsAllowed: Boolean,
        alreadyAsked: Boolean
    ): NotificationPermissionState = when {
        permissionGranted && notificationsAllowed -> NotificationPermissionState.GRANTED

        // Owed the dialog. This is checked before [notificationsAllowed] because a fresh
        // Android 13 install reports notifications as disabled purely for want of the
        // permission; reading that as "switched off" would send every new user to the system
        // settings screen to fix something the runtime request was about to fix for them.
        !permissionGranted && !alreadyAsked -> NotificationPermissionState.REQUESTABLE

        // Everything else needs the system settings screen: either notifications are switched
        // off for the app, which no runtime request can undo, or the dialog has already been
        // shown and Android auto-rejects further requests with no UI at all.
        else -> NotificationPermissionState.BLOCKED
    }
}
