import Foundation
import shared
import UIKit

/// Who the signed-in person is: `GET /api/me`.
///
/// The server keeps people in `notetakerUsers`, and only the signed-in
/// person's own account is ever read: everything else `UserRepository` offers
/// belonged to TAS's team screens, which this app does not have, and answers
/// "nothing".
///
/// WHAT THE ANSWERS MEAN, for the session (`UserDoc`):
///  - 200: active, with the role mapped by `Role.fromCrmRole` (`admin`, `member`).
///  - 403 `inactive` or `no_profile`: a login turned off, or one with no
///    profile. Both show the "access turned off" screen.
///  - no answer, a 5xx, or a 401 even after a fresh token: `UserDocFailed`, the
///    "could not reach your account" screen with its retry.
///
/// WHEN IT ASKS: on sign-in (the session subscribes), on that screen's retry
/// (it subscribes again), and when the app comes back to the foreground. A
/// call that brought no answer is tried again by itself, as a dropped
/// Firestore listener healed itself.
///
/// THE LAST ANSWER IS KEPT ON THE PHONE, as Firestore's cache kept the
/// document, so a meeting can still be recorded on a cold start with no
/// signal. With nothing kept for this person, no signal is the "could not
/// reach" screen. And once an answer has been given, a later call that brings
/// none changes nothing: somebody already in is not thrown out by a tunnel.
final class ApiUserRepository: UserRepository {

    private static let cachePrefix = "api.account."
    private static let blockedCodes: Set<String> = ["inactive", "no_profile"]
    private static let firstRetry: Double = 30
    private static let maxRetry: Double = 300

    fileprivate enum Asked {
        case answered(UserDoc)
        case noAnswer(String)
    }

    func getUser(uid: String) async throws -> User? {
        if case let .answered(doc) = await ask(uid: uid) { return (doc as? UserDocPresent)?.user }
        return nil
    }

    func observeUser(uid: String, onChange: @escaping (User?) -> Void) -> Cancellable {
        observeUserDocument(uid: uid) { doc in onChange((doc as? UserDocPresent)?.user) }
    }

    func observeUserDocument(uid: String, onChange: @escaping (UserDoc) -> Void) -> Cancellable {
        let wake = Wake()
        let observer = NotificationCenter.default.addObserver(
            forName: UIApplication.willEnterForegroundNotification, object: nil, queue: nil
        ) { _ in wake.ring() }

        let task = Task.detached { [self] in
            var told = false
            var wait = Self.firstRetry
            while !Task.isCancelled {
                switch await self.ask(uid: uid) {
                case let .answered(doc):
                    onChange(doc)
                    told = true
                    wait = Self.firstRetry
                    // Until the app comes back to the foreground.
                    await wake.sleep(atMost: .infinity)
                case let .noAnswer(reason):
                    if !told {
                        if let cached = self.cached(uid: uid) {
                            onChange(UserDocPresent(user: cached))
                        } else {
                            onChange(UserDocFailed(reason: reason))
                        }
                        told = true
                    }
                    await wake.sleep(atMost: wait)
                    wait = min(wait * 2, Self.maxRetry)
                }
            }
        }
        return HandleCancellable {
            task.cancel()
            NotificationCenter.default.removeObserver(observer)
        }
    }

    fileprivate func ask(uid: String) async -> Asked {
        let reply: NotetakerApi.Answer
        do {
            reply = try await NotetakerApi.call("GET", "/api/me")
        } catch {
            let reason = (error as? NotetakerApi.NoAnswer)?.reason ?? error.localizedDescription
            NSLog("[API] Account not reached: %@", reason)
            return .noAnswer(reason)
        }
        if reply.ok {
            remember(uid: uid, me: reply.body)
            return .answered(UserDocPresent(user: Self.user(uid: uid, me: reply.body)))
        }
        if reply.status == 403, Self.blockedCodes.contains(reply.code) {
            remember(uid: uid, me: ["blocked": true])
            return .answered(UserDocPresent(user: Self.user(uid: uid, me: ["blocked": true])))
        }
        return .noAnswer("HTTP \(reply.status)")
    }

    private static func user(uid: String, me: [String: Any]) -> User {
        if me["blocked"] as? Bool == true {
            return User.companion.fromCrm(uid: uid, name: "", email: nil, role: nil, status: UserStatus.suspended)
        }
        let email = (me["email"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let id = (me["uid"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? uid
        return User.companion.fromCrm(
            uid: id,
            name: me["name"] as? String ?? "",
            email: email,
            role: Role.companion.fromCrmRole(role: me["role"] as? String),
            status: UserStatus.active
        )
    }

    private func remember(uid: String, me: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: me) else { return }
        UserDefaults.standard.set(data, forKey: Self.cachePrefix + uid)
    }

    private func cached(uid: String) -> User? {
        guard let data = UserDefaults.standard.data(forKey: Self.cachePrefix + uid),
              let me = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else { return nil }
        return Self.user(uid: uid, me: me)
    }

    /* ------------------------------ TAS's team screens: not in this app */

    func observeDirectReports(
        parentId: String,
        viewerUid: String,
        limit: Int32,
        onChange: @escaping ([User]) -> Void
    ) -> Cancellable {
        onChange([])
        return HandleCancellable {}
    }

    func observeCreatedBy(
        viewerUid: String,
        limit: Int32,
        onChange: @escaping ([User]?) -> Void
    ) -> Cancellable {
        onChange([])
        return HandleCancellable {}
    }

    func organisationLeaders() async throws -> [User] { [] }

    func leadersBelow(uid: String, limit: Int32) async throws -> [User] { [] }

    func subtreeOf(uid: String, limit: Int32) async throws -> [User] { [] }

    func peopleIn(localityId: String, viewerUid: String, limit: Int32) async throws -> [User] { [] }

    func countsByRole(uid: String) async throws -> [Role: KotlinInt] { [:] }

    func createUser(user: User, password: String) async throws -> String {
        throw NSError(domain: "Api", code: 1, userInfo: [NSLocalizedDescriptionKey: "Logins are made by an admin."])
    }

    func updateUser(uid: String, changes: [String: Any]) async throws {
        throw NSError(domain: "Api", code: 1, userInfo: [NSLocalizedDescriptionKey: "Logins are changed by an admin."])
    }

    func findDuplicateByPhone(phone: String, callerUid: String) async throws -> User? { nil }

    func searchTeam(callerUid: String, term: String, limit: Int32) async throws -> [User] { [] }
}

/// A sleep that the app coming back to the foreground cuts short.
private final class Wake: @unchecked Sendable {
    private let lock = NSLock()
    private var rung = false

    func ring() {
        lock.lock(); rung = true; lock.unlock()
    }

    private func takeRing() -> Bool {
        lock.lock(); defer { lock.unlock() }
        let was = rung
        rung = false
        return was
    }

    /// Returns after `seconds`, when rung, or when the task is cancelled.
    func sleep(atMost seconds: Double) async {
        let started = Date()
        while !Task.isCancelled {
            if takeRing() { return }
            if Date().timeIntervalSince(started) >= seconds { return }
            try? await Task.sleep(nanoseconds: 1_000_000_000)
        }
    }
}
