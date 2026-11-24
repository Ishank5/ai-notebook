import SwiftUI

/// The AI Notebook palette, the same tokens as Android and the admin panel
/// Light only.
struct AppColors {
    /// App background.
    let bg = Color(hex: 0xF4F6F5)
    /// Cards and sheets.
    let surface = Color(hex: 0xFFFFFF)
    /// Inputs and quiet fills.
    let surfaceAlt = Color(hex: 0xEEF2F0)
    /// Hairline borders.
    let line = Color(hex: 0xE1E7E4)

    let ink = Color(hex: 0x0F1B19)
    let inkMuted = Color(hex: 0x5A6B67)
    let inkFaint = Color(hex: 0x8C9A96)

    /// Pine teal: main buttons, links, the selected tab.
    let primary = Color(hex: 0x0D7A68)
    let primaryPressed = Color(hex: 0x09604F)
    let primaryTint = Color(hex: 0xE3F3EF)
    let onPrimary = Color.white

    /// The record button and the live dot.
    let record = Color(hex: 0xE5484D)
    let recordTint = Color(hex: 0xFDECEC)

    /// Warnings and paused.
    let amber = Color(hex: 0xB7791F)
    let amberTint = Color(hex: 0xFBF3E2)

    /// Ready.
    let success = Color(hex: 0x1F8A4C)
    let successTint = Color(hex: 0xE5F4EA)
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}
