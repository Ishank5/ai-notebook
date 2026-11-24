import SwiftUI

// The AI Notebook logo mark, drawn, so no image is needed. This one file is
// compiled into both the app and the RecorderWidget extension (see the
// exception set in project.pbxproj), so it uses nothing but SwiftUI.
// design/render-app-icon.swift draws the same shapes for the app icon.

/// The white mark alone: three rounded bars of a small waveform beside two
/// short note lines. Coordinates are fractions of the square it is drawn in.
enum LogoGeometry {
    static let barWidth: CGFloat = 0.08
    /// (left edge, height) of each bar, centred on the middle line.
    static let bars: [(x: CGFloat, height: CGFloat)] = [(0.20, 0.26), (0.32, 0.44), (0.44, 0.32)]
    /// (left, right, centre y) of each note line.
    static let lines: [(from: CGFloat, to: CGFloat, y: CGFloat)] = [(0.60, 0.80, 0.43), (0.60, 0.73, 0.57)]
    /// The square's corner, as a fraction of its side.
    static let cornerRadius: CGFloat = 0.28

    static func markPath(in rect: CGRect) -> Path {
        let s = min(rect.width, rect.height)
        let ox = rect.minX + (rect.width - s) / 2
        let oy = rect.minY + (rect.height - s) / 2
        let w = barWidth * s
        var path = Path()
        for bar in bars {
            let h = bar.height * s
            path.addRoundedRect(
                in: CGRect(x: ox + bar.x * s, y: oy + s / 2 - h / 2, width: w, height: h),
                cornerSize: CGSize(width: w / 2, height: w / 2)
            )
        }
        for line in lines {
            path.addRoundedRect(
                in: CGRect(x: ox + line.from * s, y: oy + line.y * s - w / 2, width: (line.to - line.from) * s, height: w),
                cornerSize: CGSize(width: w / 2, height: w / 2)
            )
        }
        return path
    }
}

/// The rounded square in pine teal with the white mark.
struct LogoMark: View {
    var size: CGFloat = 40
    var fill: Color = Color(.sRGB, red: 0x0D / 255, green: 0x7A / 255, blue: 0x68 / 255, opacity: 1)
    var mark: Color = .white

    var body: some View {
        Canvas { context, canvas in
            let rect = CGRect(origin: .zero, size: canvas)
            context.fill(
                Path(roundedRect: rect, cornerRadius: canvas.width * LogoGeometry.cornerRadius, style: .continuous),
                with: .color(fill)
            )
            context.fill(LogoGeometry.markPath(in: rect), with: .color(mark))
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// The white mark alone, in any colour, for small places such as the Dynamic Island.
struct LogoGlyph: View {
    var size: CGFloat = 20
    var color: Color = .white

    var body: some View {
        Canvas { context, canvas in
            // The mark spans 0.20 to 0.80 of its square; drawn a little larger
            // here so the glyph fills the space it is given.
            let grow = canvas.width * 0.5
            let rect = CGRect(x: -grow / 2, y: -grow / 2, width: canvas.width + grow, height: canvas.height + grow)
            context.fill(LogoGeometry.markPath(in: rect), with: .color(color))
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
