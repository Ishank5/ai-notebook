import Foundation
import shared

/// Light, dark, or the phone's own setting, surviving a force-quit.
///
/// The default is the PHONE'S. The override is stored because the phone's
/// setting is not always theirs to change — a shared handset, or an automatic
/// schedule that flips to dark at six in the evening, which is exactly when a
/// booth worker is outside in the last of the daylight.
enum AppearanceStore {
    private static let key = "tas.appearance"

    static func load() -> Appearance {
        Appearance.companion.fromCode(code: UserDefaults.standard.string(forKey: key))
    }

    static func save(_ appearance: Appearance) {
        UserDefaults.standard.set(appearance.code, forKey: key)
    }
}
