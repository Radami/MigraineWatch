import SwiftUI

@main
struct MigraineWatchApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView(graph: AppDelegate.graph)
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            // Background refresh runs when iOS decides, not hourly, so every open reconciles too.
            case .active:
                BackgroundRefresh.runNow()

            // Each trip to the background books the next refresh; iOS keeps no repeating schedule.
            case .background:
                BackgroundRefresh.schedule()

            default:
                break
            }
        }
    }
}
