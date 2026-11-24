import FirebaseCore
import shared
import SwiftUI
import UIKit
import UserNotifications

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    /*
     * CONFIGURED HERE, NOT ONLY IN THE DELEGATE. SwiftUI builds this struct
     * before `application(_:didFinishLaunchingWithOptions:)` runs, and anything
     * that touches Firebase in between logs "The default Firebase app has not
     * yet been configured" and quietly does nothing. The delegate still calls
     * it defensively; `configure()` is safe to attempt twice because both sites
     * check first.
     */
    init() {
        if FirebaseApp.app() == nil {
            FirebaseApp.configure()
        }
        Self.forgetLegacyClaudeKey()
        AppTheme.styleSystemBars()
    }

    /*
     * ONE-TIME CLEANUP, and it earns its place on the launch path.
     *
     * The notetaker used to ask each person to paste their own Anthropic key
     * in, and kept it in UserDefaults. The field and the code that read it are
     * gone, but an upgrade does not empty a defaults entry — so on every
     * handset that ran an older build, a live `sk-ant-…` would sit in the app
     * container indefinitely, in something the whole point of this change was
     * to stop. Removing the key is cheap and idempotent; leaving it is the
     * exact leak that was being fixed.
     *
     * SAFE TO DELETE once no device can still be running a build from before
     * the key moved to Secret Manager.
     */
    private static func forgetLegacyClaudeKey() {
        UserDefaults.standard.removeObject(forKey: "tas.notetaker.claudeKey")
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                // the Live Activity's Stop.
                .onOpenURL { url in RecorderController.shared.open(url) }
        }
    }
}

/// The delegate exists for the recorder: its background work, and its local
/// notifications — shown while the app is open, and opening the recorder
/// when tapped.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // BEFORE anything touches `Auth.auth()`. Reading it first logs "The
        // default Firebase app has not yet been configured" and drops the APNs
        // token on the floor, silently disabling the very verification path
        // this delegate exists to enable.
        if FirebaseApp.app() == nil {
            FirebaseApp.configure()
        }

        /*
         * WITHOUT THIS DELEGATE, a notification that arrives while the app is
         * OPEN is delivered silently and drawn nowhere — no banner, no sound.
         * iOS assumes a foreground app will present it itself, and most of the
         * time nobody notices until somebody says "I never got told".
         */
        UNUserNotificationCenter.current().delegate = self

        /*
         * THE RECORDER, BEFORE LAUNCH ENDS. Background tasks must be registered
         * now or iOS refuses them later; and the first pass over the queue is
         * what finds a recording the app died in the middle of — repaired and
         * offered for Continue before anybody opens the recorder, and anything
         * unsent on its way.
         */
        RecorderBackground.register()
        Task { @MainActor in await RecorderController.shared.syncNow() }
        /*
         * the planned length's warning and the Live Activity.
         * The warning's buttons must be registered before one is posted; the
         * Live Activity's Stop and Keep recording reach the recorder through
         * the hook; and nothing records at launch, so a Live Activity still
         * showing was left by a killed app.
         */
        MainActor.assumeIsolated {
            RecorderAlerts.registerCategories()
            // waited for, so the app is not suspended before the recorder has done it.
            RecorderIntents.handler = { action in
                switch action {
                case .stop: await RecorderController.shared.stopAndWait()
                case .keep: await RecorderController.shared.extendAndWait()
                }
            }
            RecorderLiveActivity.endLeftovers()
        }
        // Leaving the screen with something unsent: let iOS finish it later.
        // A notification rather than the delegate method, which a SwiftUI
        // app's scenes stop the delegate from receiving.
        NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { _ in
            RecorderBackground.schedule()
        }
        // back in front: the recorder catches up on what it could not do in the background.
        NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { _ in
            MainActor.assumeIsolated { RecorderController.shared.cameToForeground() }
        }
        return true
    }

    /// Somebody TAPPED a notification — open what it was about.
    ///
    /// Distinct from `willPresent`, which only decides how an arriving
    /// notification is displayed. This is the tap, and it fires whether the app
    /// was open, backgrounded, or launched by the tap itself.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        // the warning's buttons act on the recording without opening the app.
        switch response.actionIdentifier {
        // waited for, under background time, so a suspended app finishes what the button asked.
        case RecorderAlerts.keepAction:
            await RecorderController.shared.extendAndWait()
        case RecorderAlerts.stopAction:
            await RecorderController.shared.stopAndWait()
        default:
            await Notifications.Pending.shared.offer(
                userInfo: response.notification.request.content.userInfo
            )
        }
    }

    /// Shown even when the app is in the foreground.
    ///
    /// `.banner` and `.sound` are both asked for: the sound is the point of
    /// this notification, and returning only `.banner` would play nothing.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .sound, .list]
    }

}
