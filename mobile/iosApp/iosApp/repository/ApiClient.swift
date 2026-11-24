import FirebaseAuth
import Foundation
import shared

/// The AI Notebook API (the `ntApi` Cloud Function), as the phone calls it.
///
/// Firebase is used for sign-in only. Everything else (who someone is, upload
/// links, closing a recording, its status, the meetings) is a plain HTTPS call,
/// carrying the Firebase ID token as a bearer token.
///
/// The address is `ApiBaseUrl` in Info.plist, filled from `API_BASE_URL` in
/// `Configuration/Config.xcconfig`. Paths start with `/api/`, so a call goes to
/// `.../ntApi/api/...`.
enum NotetakerApi {

    /// What the server said: its HTTP status and its JSON body (empty when it had none).
    struct Answer {
        let status: Int
        let body: [String: Any]

        var ok: Bool { (200..<300).contains(status) }

        /// The API's reason code: `inactive`, `no_profile`, `recorder.err.*` …
        var code: String { body["code"] as? String ?? "" }
    }

    /// No answer came back at all: no signal, a timeout, a token that could
    /// not be had offline.
    struct NoAnswer: Error {
        let reason: String
    }

    private static let defaultBaseUrl = "https://us-central1-team-akshay-sharma.cloudfunctions.net/ntApi"

    static var baseUrl: String {
        let configured = (Bundle.main.object(forInfoDictionaryKey: "ApiBaseUrl") as? String)?
            .trimmingCharacters(in: .whitespaces) ?? ""
        let base = configured.hasPrefix("http") ? configured : defaultBaseUrl
        return base.hasSuffix("/") ? String(base.dropLast()) : base
    }

    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.default
        // The callables' own default was 70 seconds; a finalize lists the bucket.
        configuration.timeoutIntervalForRequest = 60
        return URLSession(configuration: configuration)
    }()

    /// One call. Throws `NoAnswer` only when nothing came back.
    ///
    /// A 401 IS ASKED AGAIN ONCE, with a token refreshed on the spot. A token
    /// expires every hour, and one cached across a night in a pocket is the
    /// usual reason the API says "sign in again" to somebody who is signed in.
    static func call(_ method: String, _ path: String, body: [String: Any]? = nil) async throws -> Answer {
        let data = try body.map { try JSONSerialization.data(withJSONObject: $0) }
        let (status, reply) = try await exchange(method, path, data)
        let json = (try? JSONSerialization.jsonObject(with: reply)) as? [String: Any] ?? [:]
        return Answer(status: status, body: json)
    }

    /// The same call with the body as JSON text both ways, for the shared
    /// meetings repository, which reads the JSON itself.
    static func callText(_ method: String, _ path: String, bodyText: String?) async throws -> (status: Int, body: String) {
        let (status, reply) = try await exchange(method, path, bodyText.map { Data($0.utf8) })
        return (status, String(data: reply, encoding: .utf8) ?? "")
    }

    private static func exchange(_ method: String, _ path: String, _ body: Data?) async throws -> (Int, Data) {
        let first = try await send(method, path, body, token: try await token(forceRefresh: false))
        if first.0 != 401 { return first }
        return try await send(method, path, body, token: try await token(forceRefresh: true))
    }

    private static func token(forceRefresh: Bool) async throws -> String? {
        guard let user = Auth.auth().currentUser else { return nil }
        do {
            return try await user.getIDTokenResult(forcingRefresh: forceRefresh).token
        } catch {
            throw NoAnswer(reason: "No ID token: \(error.localizedDescription)")
        }
    }

    private static func send(_ method: String, _ path: String, _ body: Data?, token: String?) async throws -> (Int, Data) {
        guard let url = URL(string: baseUrl + path) else { throw NoAnswer(reason: "Bad address") }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            request.httpBody = body
        }
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { throw NoAnswer(reason: "No HTTP answer") }
            return (http.statusCode, data)
        } catch let error as NoAnswer {
            throw error
        } catch {
            throw NoAnswer(reason: error.localizedDescription)
        }
    }

    /// A refused recording call as the recorder's key, mirroring how TAS read
    /// a Functions error: the server's own `recorder.err.*` key when it gave
    /// one; otherwise signed out, inactive, or a server fault.
    ///
    /// Never "no signal". This is only called with an answer in
    /// hand, and TAS read every other 4xx as no signal, which stopped the whole
    /// upload run behind one recording. No answer at all is `NoAnswer`.
    static func recorderError(_ answer: Answer) -> String {
        for candidate in [answer.code, answer.body["error"] as? String ?? ""]
        where candidate.hasPrefix("recorder.err.") {
            return candidate
        }
        if answer.status == 401 { return RecorderErrors.shared.SIGNED_OUT }
        if answer.status == 403, ["inactive", "no_profile"].contains(answer.code) {
            return RecorderErrors.shared.INACTIVE
        }
        return RecorderErrors.shared.SERVER
    }
}
