import SwiftUI
import shared

/// The way in: the logo, then one card with an email, a password and a button.
/// A notice shows only when there is something true to say: a failed sign-in,
/// or why the last session ended.
struct SignInView: View {
    @EnvironmentObject private var theme: AppTheme
    @StateObject private var viewModel = SignInViewModel()

    private enum Field { case email, password }
    @FocusState private var focus: Field?

    /// Why the last session ended, when it ended for a reason worth naming.
    var signedOutReason: SignOutReason?

    /// The error if there is one, and otherwise the reason this screen was
    /// shown, until somebody starts typing.
    private var noticeKey: String? {
        if let key = viewModel.errorKey { return key }
        guard !viewModel.touched else { return nil }
        return signedOutReason?.messageKey
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 28) {
                VStack(spacing: 14) {
                    LogoMark(size: 64)
                    Text(theme.tr("common.app.name"))
                        .font(Manrope.font(.extrabold, 28))
                        .tracking(-0.5)
                        .foregroundStyle(theme.colors.ink)
                }
                .padding(.top, 56)

                card
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
        }
        .scrollDismissesKeyboard(.interactively)
        .scrollBounceBehavior(.basedOnSize)
        .background(theme.colors.bg.ignoresSafeArea())
        .animation(.easeOut(duration: 0.18), value: noticeKey)
    }

    private var card: some View {
        AppCard(padding: 20) {
            VStack(alignment: .leading, spacing: 16) {
                Text(theme.tr("auth.signIn"))
                    .font(theme.type.heading)
                    .foregroundStyle(theme.colors.ink)

                VStack(alignment: .leading, spacing: 6) {
                    fieldLabel(theme.tr("auth.username"))
                    TextField("", text: usernameBinding, prompt: prompt(theme.tr("auth.usernamePlaceholder")))
                        .keyboardType(.emailAddress)
                        .textContentType(.username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .submitLabel(.next)
                        .onSubmit { focus = .password }
                        .focused($focus, equals: .email)
                        .inputStyle(theme)
                        .fieldBox(focused: focus == .email)
                }

                VStack(alignment: .leading, spacing: 6) {
                    fieldLabel(theme.tr("auth.password"))
                    HStack(spacing: 8) {
                        Group {
                            if viewModel.passwordVisible {
                                TextField("", text: passwordBinding, prompt: prompt(theme.tr("auth.passwordPlaceholder")))
                            } else {
                                SecureField("", text: passwordBinding, prompt: prompt(theme.tr("auth.passwordPlaceholder")))
                            }
                        }
                        .textContentType(.password)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .submitLabel(.go)
                        .onSubmit(submit)
                        .focused($focus, equals: .password)
                        .inputStyle(theme)

                        Button {
                            viewModel.togglePasswordVisible()
                            focus = .password
                        } label: {
                            Image(systemName: viewModel.passwordVisible ? "eye.slash" : "eye")
                                .font(.system(size: 16, weight: .medium))
                                .foregroundStyle(theme.colors.inkFaint)
                                .frame(width: 32, height: AppShape.tap)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(
                            theme.tr(viewModel.passwordVisible ? "auth.hidePassword" : "auth.showPassword")
                        )
                    }
                    .fieldBox(focused: focus == .password)
                }

                if let noticeKey {
                    Notice(text: theme.tr(noticeKey))
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }

                AppButton(
                    title: viewModel.busy ? theme.tr("auth.signingIn") : theme.tr("auth.signIn"),
                    enabled: viewModel.canSubmit,
                    busy: viewModel.busy,
                    action: submit
                )
                .padding(.top, 4)
            }
        }
    }

    private var usernameBinding: Binding<String> {
        Binding(get: { viewModel.username }, set: { viewModel.setUsername($0) })
    }

    private var passwordBinding: Binding<String> {
        Binding(get: { viewModel.password }, set: { viewModel.setPassword($0) })
    }

    private func fieldLabel(_ text: String) -> some View {
        Text(text)
            .font(theme.type.smallStrong)
            .foregroundStyle(theme.colors.inkMuted)
    }

    private func prompt(_ text: String) -> Text {
        Text(text).foregroundColor(theme.colors.inkFaint)
    }

    private func submit() {
        focus = nil
        viewModel.onSubmit()
    }
}

private extension View {
    func inputStyle(_ theme: AppTheme) -> some View {
        self
            .font(theme.type.bodyMedium)
            .foregroundStyle(theme.colors.ink)
            .tint(theme.colors.primary)
    }
}
