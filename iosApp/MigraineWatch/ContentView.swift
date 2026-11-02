import Shared
import SwiftUI
import UserNotifications

/// A stand-in screen until the real UI is chosen: enough to drive a refresh and see the
/// warnings it queues.
struct ContentView: View {

    /// A place with a forecast to test against, set by hand until onboarding exists on iOS.
    private static let testLocation = LocationData(
        source: "manual",
        lat: 47.3769,
        lon: 8.5417,
        name: "Zurich, Switzerland",
        timezone: "Europe/Zurich"
    )

    let graph: IosAppGraph

    @State private var status = "Not refreshed yet"
    @State private var pending: [UNNotificationRequest] = []

    var body: some View {
        NavigationStack {
            List {
                Section("Forecast") {
                    Text(status)
                    Button("Use \(Self.testLocation.name)") {
                        Task { await useTestLocation() }
                    }
                    Button("Use high sensitivity") {
                        Task { await useHighSensitivity() }
                    }
                    Button("Refresh now") {
                        Task { await refresh() }
                    }
                }

                Section("Pending warnings") {
                    if pending.isEmpty {
                        Text("None")
                    }
                    ForEach(pending, id: \.identifier) { request in
                        PendingWarningRow(request: request)
                    }
                }
            }
            .navigationTitle("MigraineWatch")
            .task { await loadPending() }
        }
    }

    private func useTestLocation() async {
        do {
            try await graph.userPreferences.saveLocation(data: Self.testLocation)
            await refresh()
        } catch {
            status = "Could not save the location: \(error.localizedDescription)"
        }
    }

    /// The lowest threshold, so a modest forecast swing is enough to queue a warning to test with.
    private func useHighSensitivity() async {
        do {
            try await graph.userPreferences.setAlertSensitivity(sensitivity: .high)
            await refresh()
        } catch {
            status = "Could not save the sensitivity: \(error.localizedDescription)"
        }
    }

    private func refresh() async {
        status = "Refreshing…"
        let upToDate = await BackgroundRefresh.refresh()
        status = upToDate ? "Up to date" : "Refresh did not complete"
        await loadPending()
    }

    private func loadPending() async {
        pending = await UNUserNotificationCenter.current().pendingNotificationRequests()
    }
}

private struct PendingWarningRow: View {
    let request: UNNotificationRequest

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(request.content.title).font(.headline)
            Text(request.content.body)
            if let fireDate {
                Text("Shows \(fireDate.formatted(date: .abbreviated, time: .shortened))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    /// Warnings ahead use a calendar date; one due now goes out on a short timer.
    private var fireDate: Date? {
        switch request.trigger {
        case let calendar as UNCalendarNotificationTrigger:
            return calendar.nextTriggerDate()
        case let timer as UNTimeIntervalNotificationTrigger:
            return timer.nextTriggerDate()
        default:
            return nil
        }
    }
}
