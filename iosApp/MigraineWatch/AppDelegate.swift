import Shared
import UIKit
import UserNotifications

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {

    /// One for the process: it owns the database and the settings file.
    static let graph = IosAppGraph()

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Registration has to happen before launch finishes, or iOS drops the task.
        BackgroundRefresh.register()

        UNUserNotificationCenter.current().delegate = self
        requestNotificationPermission()
        return true
    }

    /// Shows warnings as banners while the app is open too; by default iOS keeps them silent.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .sound, .list]
    }

    /// Asked at launch for now; the onboarding flow will take this over, as it does on Android.
    private func requestNotificationPermission() {
        Task {
            do {
                let granted = try await UNUserNotificationCenter.current()
                    .requestAuthorization(options: [.alert, .sound])

                // Warnings skipped while posting was not allowed can be scheduled now.
                if granted { BackgroundRefresh.runNow() }
            } catch {
                NSLog("Notification permission request failed: \(error)")
            }
        }
    }
}
