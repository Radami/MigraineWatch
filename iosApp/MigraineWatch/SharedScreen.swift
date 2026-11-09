import SwiftUI

/// Shared Compose UI, hosted in SwiftUI.
struct SharedScreen: UIViewControllerRepresentable {
    let makeController: () -> UIViewController

    func makeUIViewController(context: Context) -> UIViewController {
        makeController()
    }

    /// Compose redraws itself from its own state; SwiftUI has nothing to pass in.
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
