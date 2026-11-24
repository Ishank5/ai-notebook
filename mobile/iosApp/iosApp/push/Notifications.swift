import UIKit
import UserNotifications

/// Taps on the recorder's own local notifications.
///
/// Firebase is for sign-in only, so there are no push notifications. What
/// stays is the part the recorder needs: a tapped local notification opening
/// the recorder.
enum Notifications {

    /// A tapped notification, waiting for a screen to act on it.
    ///
    /// A process-wide one-shot: the tap reaches the app delegate, which has no
    /// navigation state, and the tab view observes this. Consumed, not just
    /// read, so coming back from the background does not jump again.
    @MainActor
    final class Pending: ObservableObject {
        static let shared = Pending()
        @Published var openRecorder = false

        /// What the tapped notification's `userInfo` says it is about.
        func offer(userInfo: [AnyHashable: Any]) {
            if userInfo["kind"] as? String == "recorder" { openRecorder = true }
        }

        func consumeRecorder() { openRecorder = false }

        /// Dropped when the session ends, so a tap never outlives its owner.
        func clear() {
            openRecorder = false
        }
    }
}
