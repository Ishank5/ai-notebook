import SwiftUI

/// The first frame: the logo mark and the name on the app background, held
/// until the session is known. The launch screen is the same colour, so the
/// hand-over is seamless.
struct SplashView: View {
    @EnvironmentObject private var theme: AppTheme

    @State private var visible = false

    var body: some View {
        VStack(spacing: 16) {
            LogoMark(size: 76)
            Text(theme.tr("common.app.name"))
                .font(Manrope.font(.extrabold, 28))
                .tracking(-0.5)
                .foregroundStyle(theme.colors.ink)
        }
        .opacity(visible ? 1 : 0)
        .scaleEffect(visible ? 1 : 0.97)
        .animation(.easeOut(duration: 0.4), value: visible)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(theme.colors.bg.ignoresSafeArea())
        .onAppear { visible = true }
    }
}

/// The logo mark beside the wordmark, as a single row.
struct Wordmark: View {
    @EnvironmentObject private var theme: AppTheme
    var markSize: CGFloat = 34
    var textSize: CGFloat = 22

    var body: some View {
        HStack(spacing: 10) {
            LogoMark(size: markSize)
            Text(theme.tr("common.app.name"))
                .font(Manrope.font(.extrabold, textSize))
                .tracking(-0.4)
                .foregroundStyle(theme.colors.ink)
                .lineLimit(1)
        }
    }
}
