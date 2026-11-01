import BackgroundTasks
import Shared

/// Fetches the forecast and reconciles warnings, in the background as Android's worker does.
enum BackgroundRefresh {

    static let taskIdentifier = "com.radami.migrainewatch.refresh"

    /// Open-Meteo publishes hourly, so asking sooner gains nothing. iOS may well wait longer.
    private static let interval: TimeInterval = 60 * 60

    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskIdentifier, using: nil) { task in
            guard let refreshTask = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            handle(refreshTask)
        }
    }

    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: taskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: interval)

        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            NSLog("Could not schedule a background refresh: \(error)")
        }
    }

    static func runNow() {
        Task { await refresh() }
    }

    /// @return whether the app is now up to date.
    @discardableResult
    static func refresh() async -> Bool {
        do {
            let upToDate: KotlinBoolean? = try await AppDelegate.graph.refreshAndReconcile()
            return upToDate?.boolValue ?? false
        } catch {
            NSLog("Refresh failed: \(error)")
            return false
        }
    }

    private static func handle(_ task: BGAppRefreshTask) {
        schedule()

        let work = Task {
            let upToDate = await refresh()
            task.setTaskCompleted(success: upToDate)
        }
        task.expirationHandler = { work.cancel() }
    }
}
