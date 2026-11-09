import MigraineWatchKit
import UIKit
import UserNotifications

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {

    /// One for the process: it owns the database and the settings file.
    static let graph = IosAppGraph()

    /// What the shared Compose screens are built from, read from the same graph.
    static let uiDependencies = IosUiDependencies(graph: graph)

    /// Notification taps, held until the shared app opens the tab they ask for.
    static let notificationTaps = NotificationTaps()

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Registration has to happen before launch finishes, or iOS drops the task.
        BackgroundRefresh.register()

        // Rebuilds queued warnings whenever a series lands or the alert settings change.
        AppDelegate.graph.start()

        // Permission is asked by onboarding, once the user has seen what the app is for. Set
        // before launch finishes, so a tap that launched the app is still delivered here.
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    /// Shows warnings as banners while the app is open too; by default iOS keeps them silent.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .sound, .list]
    }

    /// A tapped warning opens Pressure, whether the tap launched the app or arrived while it ran.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        // Only the tap itself opens the app; any other response leaves the screen as it is.
        guard response.actionIdentifier == UNNotificationDefaultActionIdentifier else { return }

        AppDelegate.notificationTaps.warningTapped()
    }
}
