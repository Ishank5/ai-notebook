import SwiftUI
import shared

/// Who you are, and the way out.
struct ProfileView: View {
    @EnvironmentObject private var theme: AppTheme

    let user: User
    let onSignOut: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                AppCard(padding: 20) {
                    HStack(spacing: 14) {
                        Avatar(initials: user.initials, size: 52)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(user.name.isEmpty ? (user.email ?? "") : user.name)
                                .font(theme.type.subheading)
                                .foregroundStyle(theme.colors.ink)
                            if let email = user.email, !user.name.isEmpty {
                                Text(email)
                                    .font(theme.type.small)
                                    .foregroundStyle(theme.colors.inkMuted)
                            }
                        }
                        Spacer(minLength: 8)
                        AppChip(text: roleLabel, tone: isAdmin ? .primary : .neutral)
                    }
                }

                AppButton(title: theme.tr("common.nav.logOut"), tone: .outline, action: onSignOut)

                Text("\(theme.tr("common.app.name")) \(version)")
                    .font(theme.type.caption)
                    .foregroundStyle(theme.colors.inkFaint)
                    .padding(.top, 8)
            }
            .padding(16)
        }
        .background(theme.colors.bg.ignoresSafeArea())
        .navigationTitle(theme.tr("profile.title"))
        .navigationBarTitleDisplayMode(.inline)
    }

    /// The server has two roles: admin and member.
    private var isAdmin: Bool {
        guard let id = user.role?.id else { return false }
        return id == "admin" || id == "superAdmin"
    }

    private var roleLabel: String { isAdmin ? "Admin" : "Member" }

    private var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
    }
}
