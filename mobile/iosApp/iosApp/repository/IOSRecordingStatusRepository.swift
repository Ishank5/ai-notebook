import Foundation
import shared

/// What the server has made of this person's recordings: their status,
/// nothing more, from `POST /api/recordings/statuses`.
///
/// TAS read each `recordings/{id}` document from Firestore. The
/// API answers for many ids at once and, as the rule did, only for the
/// caller's own recordings: anybody else's id is simply left out.
///
/// NEVER THROWS. A recording that cannot be read — no signal, not there any
/// more — is simply left out, and its row goes on saying "Sent".
final class IOSRecordingStatusRepository: RecordingStatusRepository {

    /// The API takes at most this many ids per call.
    private static let maxIds = 100

    func serverStatuses(ids: [String]) async throws -> [String: String] {
        var statuses: [String: String] = [:]
        var unique: [String] = []
        for id in ids where !unique.contains(id) { unique.append(id) }
        var start = 0
        while start < unique.count {
            let batch = Array(unique[start..<min(start + Self.maxIds, unique.count)])
            start += Self.maxIds
            guard let reply = try? await NotetakerApi.call("POST", "/api/recordings/statuses", body: ["ids": batch]),
                  reply.ok,
                  let answered = reply.body["statuses"] as? [String: Any]
            else { continue }
            for id in batch {
                if let status = answered[id] as? String { statuses[id] = status }
            }
        }
        return statuses
    }
}
