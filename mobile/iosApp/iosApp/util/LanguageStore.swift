import Foundation
import shared

/// The app's language.
///
/// the app is English only, so this is always English and
/// nothing can choose another. It stays because the recorder's notifications
/// (kept byte-identical to TAS) read it from here; a language a device chose
/// under an older build is no longer read.
enum LanguageStore {
    static func load() -> Language {
        Language.english
    }
}
