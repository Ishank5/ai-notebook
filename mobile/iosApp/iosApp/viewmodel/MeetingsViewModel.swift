import Foundation
import shared

/// The Meetings tab: the person's own recordings as the server has them, newest first.
@MainActor
final class MeetingsViewModel: ObservableObject {

    @Published private(set) var rows: [MeetingRow] = []
    @Published private(set) var loading = false
    @Published private(set) var loadedOnce = false
    @Published private(set) var problem: Error?

    private let load = LoadMeetingsUseCase(repository: Meetings.repository)

    /// How often a visible list or meeting is read again while one is uploading or processing.
    static let workingRefreshNanos: UInt64 = 5_000_000_000

    /// A meeting on the list is still uploading or processing.
    var working: Bool { rows.contains { $0.working } }

    func refresh() async {
        guard !loading else { return }
        loading = true
        defer { loading = false }
        do {
            rows = try await load.execute(now: Int64(Date().timeIntervalSince1970 * 1000))
            problem = nil
        } catch {
            problem = error
        }
        loadedOnce = true
    }
}

/// One meeting and its summary.
@MainActor
final class MeetingDetailViewModel: ObservableObject {

    let meetingId: String

    @Published private(set) var detail: MeetingDetail?
    @Published private(set) var loading = false
    @Published private(set) var problem: Error?

    private let loadMeeting = LoadMeetingUseCase(repository: Meetings.repository)

    init(meetingId: String) {
        self.meetingId = meetingId
    }

    /// The meeting is still uploading or processing.
    var working: Bool { detail?.meeting.working ?? false }

    func load() async {
        guard !loading else { return }
        loading = true
        defer { loading = false }
        do {
            detail = try await loadMeeting.execute(id: meetingId)
            problem = nil
        } catch {
            problem = error
        }
    }
}
