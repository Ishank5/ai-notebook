import SwiftUI
import WidgetKit

/// The app's widget extension. It holds one thing: the
/// recorder's Live Activity. No home screen widgets.
@main
struct RecorderWidgetBundle: WidgetBundle {
    var body: some Widget {
        RecorderLiveActivityWidget()
    }
}
