import Foundation
import shared

/// The API for the Meetings tab. Reading the JSON is shared
/// (`ApiMeetingRepository` in `shared`); this only makes the HTTPS call, with
/// `NotetakerApi`'s token and its one retry on a 401.
final class IOSMeetingApiClient: CrmApiClient {

    func send(method: String, path: String, body: String?) async throws -> CrmResponse {
        let reply = try await NotetakerApi.callText(method, path, bodyText: body)
        return CrmResponse(status: Int32(reply.status), body: reply.body)
    }
}

/// One repository for the app.
enum Meetings {
    static let repository: MeetingRepository = ApiMeetingRepository(api: IOSMeetingApiClient())

    /// A failed call as words for the screen: the server's own sentence when it
    /// gave one, else what kind of failure it was.
    static func message(_ error: Error, theme: AppTheme) -> String {
        if let problem = (error as NSError).userInfo["KotlinException"] as? MeetingProblem {
            return problem.serverMessage ?? theme.tr(problem.key)
        }
        return theme.tr("meetings.err.network")
    }
}
