import SwiftUI
import UIKit
import shared

/// The geometry the app repeats everywhere.
enum AppShape {
    /// Cards: 20 pt corners and a 1 pt hairline.
    static let card: CGFloat = 20
    /// Inputs and small blocks.
    static let field: CGFloat = 14
    /// Pill buttons are this tall.
    static let button: CGFloat = 52
    /// The smallest comfortable tap target.
    static let tap: CGFloat = 44
}

enum AppWeight: String {
    case regular = "Regular"
    case medium = "Medium"
    case semibold = "SemiBold"
    case bold = "Bold"
    case extrabold = "ExtraBold"
}

/// Manrope, the only typeface in the app (`Resources/Fonts`, `UIAppFonts`).
enum Manrope {
    static func font(_ weight: AppWeight, _ size: CGFloat) -> Font {
        .custom("Manrope-\(weight.rawValue)", size: size)
    }

    static func uiFont(_ weight: AppWeight, _ size: CGFloat) -> UIFont {
        UIFont(name: "Manrope-\(weight.rawValue)", size: size) ?? .systemFont(ofSize: size)
    }
}

/// Every text style, named for what it is.
struct AppType {
    /// The wordmark and screen titles.
    var title: Font { Manrope.font(.extrabold, 26) }
    /// A meeting's title on its own screen.
    var heading: Font { Manrope.font(.extrabold, 22) }
    /// Card titles.
    var subheading: Font { Manrope.font(.bold, 17) }
    var body: Font { Manrope.font(.regular, 16) }
    var bodyMedium: Font { Manrope.font(.medium, 16) }
    var bodyStrong: Font { Manrope.font(.semibold, 16) }
    var small: Font { Manrope.font(.medium, 14) }
    var smallStrong: Font { Manrope.font(.semibold, 14) }
    var caption: Font { Manrope.font(.medium, 13) }
    /// Small uppercase section labels.
    var label: Font { Manrope.font(.bold, 12) }
    var button: Font { Manrope.font(.bold, 16) }
    /// The recorder's clock; always with `.monospacedDigit()`.
    var timer: Font { Manrope.font(.bold, 60) }
}

/// Everything a view needs from the theme: `theme.colors`, `theme.type`, `theme.tr`.
final class AppTheme: ObservableObject {
    /// The app is English only; the shared strings take a language all the same.
    let language: Language

    init(language: Language = Language.english) {
        self.language = language
    }

    let colors = AppColors()
    let type = AppType()

    /// `Strings.get` with the language applied.
    func tr(_ key: String, _ args: [String: Any] = [:]) -> String {
        Strings.shared.get(key: key, language: language, args: args)
    }

    /// The system bars in Manrope too, set once at launch.
    static func styleSystemBars() {
        let colors = AppColors()
        let tab = UITabBarAppearance()
        tab.configureWithOpaqueBackground()
        tab.backgroundColor = UIColor(colors.surface)
        tab.shadowColor = UIColor(colors.line)
        for item in [tab.stackedLayoutAppearance, tab.inlineLayoutAppearance, tab.compactInlineLayoutAppearance] {
            item.normal.titleTextAttributes = [
                .font: Manrope.uiFont(.semibold, 11),
                .foregroundColor: UIColor(colors.inkFaint),
            ]
            item.normal.iconColor = UIColor(colors.inkFaint)
            item.selected.titleTextAttributes = [
                .font: Manrope.uiFont(.bold, 11),
                .foregroundColor: UIColor(colors.primary),
            ]
            item.selected.iconColor = UIColor(colors.primary)
        }
        UITabBar.appearance().standardAppearance = tab
        UITabBar.appearance().scrollEdgeAppearance = tab

        let nav = UINavigationBarAppearance()
        nav.configureWithOpaqueBackground()
        nav.backgroundColor = UIColor(colors.bg)
        nav.shadowColor = .clear
        nav.titleTextAttributes = [
            .font: Manrope.uiFont(.bold, 17),
            .foregroundColor: UIColor(colors.ink),
        ]
        nav.largeTitleTextAttributes = [
            .font: Manrope.uiFont(.extrabold, 30),
            .foregroundColor: UIColor(colors.ink),
        ]
        let back = UIBarButtonItemAppearance()
        back.normal.titleTextAttributes = [.font: Manrope.uiFont(.semibold, 16)]
        nav.backButtonAppearance = back
        UINavigationBar.appearance().standardAppearance = nav
        UINavigationBar.appearance().scrollEdgeAppearance = nav
        UINavigationBar.appearance().compactAppearance = nav
    }
}
