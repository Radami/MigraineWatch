import BackgroundTasks
import MigraineWatchKit
import os

/// Fetches the forecast and reconciles warnings, in the background as Android's worker does.
enum BackgroundRefresh {

    static let taskIdentifier = "com.radami.migrainewatch.refresh"

    /// The app's own corner of the system log, so its lines can be filtered from everything else.
    private static let subsystem = "com.radami.migrainewatch"

    /// Through `os.Logger`, not `NSLog`, which reads its message as a format string: a `%` in an
    /// error's text would be taken for a placeholder and print garbage or crash. Named in full,
    /// since the shared framework has a `Logger` of its own.
    private static let log = os.Logger(subsystem: subsystem, category: "BackgroundRefresh")

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
            log.error("Could not schedule a background refresh: \(String(describing: error), privacy: .public)")
        }
    }

    /// For the app coming to the foreground: reconciles every time, fetches only once the forecast has aged.
    static func runNow() {
        Task { await refresh(.whenStale) }
    }

    /// @return whether the app is now up to date.
    @discardableResult
    static func refresh(_ policy: RefreshPolicy) async -> Bool {
        do {
            let upToDate: KotlinBoolean? = try await AppDelegate.graph.refreshAndReconcile(policy: policy)
            return upToDate?.boolValue ?? false
        } catch {
            log.error("Refresh failed: \(String(describing: error), privacy: .public)")
            return false
        }
    }

    private static func handle(_ task: BGAppRefreshTask) {
        schedule()

        let completion = TaskCompletion(task)
        let work = Task {
            completion.finish(success: await refresh(.always))
        }

        // Cancelling the Swift task doesn't reach the Kotlin coroutine doing the fetch, so the
        // task is reported done here rather than whenever that work happens to end: iOS ends
        // apps that overrun, and grants them background time less often afterwards.
        task.expirationHandler = {
            work.cancel()
            completion.finish(success: false)
        }
    }
}

/// Reports a background task's end once, whichever of the work and the expiry gets there first.
private final class TaskCompletion: @unchecked Sendable {

    private let task: BGTask
    private let finished = OSAllocatedUnfairLock(initialState: false)

    init(_ task: BGTask) {
        self.task = task
    }

    func finish(success: Bool) {
        let isFirst = finished.withLock { done in
            defer { done = true }
            return !done
        }
        guard isFirst else { return }

        task.setTaskCompleted(success: success)
    }
}
