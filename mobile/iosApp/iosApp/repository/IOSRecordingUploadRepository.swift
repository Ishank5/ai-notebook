import Foundation
import shared

/// The recorder's two server calls, `POST /api/recordings/upload-urls` and
/// `POST /api/recordings/finalize`. The payloads and answers are TAS's
/// `recordingUploadUrls` and `finalizeRecording` callables', field for field;
/// timestamps go as plain JSON numbers. Recordings carry no company.
///
/// NEVER THROWS. The shared sync reads a refusal from the result, because an
/// error thrown from Swift into a Kotlin suspend function is not something the
/// sync can tell apart from "no signal" — and those two need opposite answers.
final class IOSRecordingUploadRepository: RecordingUploadRepository {

    func uploadTargets(
        recording: LocalRecording,
        chunks: [RecordingChunk],
        device: RecorderDevice
    ) async throws -> UploadTargets {
        do {
            let body: [String: Any] = [
                // The server refuses a recording that is not the caller's.
                "ownerUid": recording.ownerUid,
                "recordingId": recording.id,
                "chunks": chunks.map { chunk -> [String: Any] in
                    [
                        "seq": NSNumber(value: chunk.seq),
                        "bytes": NSNumber(value: Int32(chunk.bytes)),
                        "md5": orNull(chunk.md5),
                    ]
                },
                "startedAt": NSNumber(value: recording.startedAt),
                "platform": device.platform,
                "appVersion": device.appVersion,
                "deviceModel": device.model,
            ]
            let reply = try await NotetakerApi.call("POST", "/api/recordings/upload-urls", body: body)
            guard reply.ok else {
                return UploadTargets(targets: [], errorKey: NotetakerApi.recorderError(reply), refusedSeqs: [])
            }
            let urls = reply.body["urls"] as? [[String: Any]] ?? []
            let targets = urls.compactMap { item -> ChunkUploadTarget? in
                guard let seq = (item["seq"] as? NSNumber)?.int32Value,
                      let url = item["url"] as? String
                else { return nil }
                return ChunkUploadTarget(
                    seq: seq,
                    url: url,
                    headers: item["headers"] as? [String: String] ?? [:]
                )
            }
            // chunks the server gave no link for, over its size limit.
            let refused = (reply.body["refused"] as? [[String: Any]] ?? []).compactMap {
                ($0["seq"] as? NSNumber).map { KotlinInt(int: $0.int32Value) }
            }
            return UploadTargets(targets: targets, errorKey: nil, refusedSeqs: refused)
        } catch {
            return UploadTargets(targets: [], errorKey: RecorderErrors.shared.NETWORK, refusedSeqs: [])
        }
    }

    func finalize(
        recording: LocalRecording,
        seqs: [KotlinInt],
        durationMs: Int64,
        gaps: [RecordingGap]
    ) async throws -> FinalizeResult {
        do {
            var data: [String: Any] = [
                "ownerUid": recording.ownerUid,
                "recordingId": recording.id,
                "seqs": seqs.map { NSNumber(value: $0.int32Value) },
                "durationMs": NSNumber(value: Int32(durationMs)),
                "endReason": (recording.endReason ?? EndReason.stopped).id,
                "gaps": gaps.map { gap -> [String: Any] in
                    [
                        "startMs": NSNumber(value: Int32(gap.startMs)),
                        "endMs": orNull(gap.endMs.map { NSNumber(value: Int32($0.int64Value)) }),
                        "reason": gap.reason.id,
                    ]
                },
                "startedAt": NSNumber(value: recording.startedAt),
            ]
            data["endedAt"] = orNull(recording.endedAt.map { NSNumber(value: $0.int64Value) })
            data["title"] = orNull(recording.title)
            data["meetingType"] = orNull(recording.meetingType)
            data["location"] = orNull(recording.location)
            data["attendees"] = orNull(recording.attendees)

            let reply = try await NotetakerApi.call("POST", "/api/recordings/finalize", body: data)
            guard reply.ok else {
                return FinalizeResult(ok: false, missing: [], errorKey: NotetakerApi.recorderError(reply))
            }
            return FinalizeResult(
                ok: reply.body["ok"] as? Bool ?? false,
                missing: (reply.body["missing"] as? [NSNumber] ?? []).map { KotlinInt(int: $0.int32Value) },
                errorKey: nil
            )
        } catch {
            return FinalizeResult(ok: false, missing: [], errorKey: RecorderErrors.shared.NETWORK)
        }
    }

    /// A missing value as JSON null, which the API reads as "not given".
    private func orNull(_ value: Any?) -> Any {
        value ?? NSNull()
    }
}

/// One chunk, PUT to its signed URL.
///
/// REFUSED MEANS THE FILE, AND NOTHING ELSE — the same rule as Android's
/// `HttpChunkTransfer`. Only Storage saying the bytes do not match their MD5,
/// or are the wrong size, can eventually drop a chunk; a signature it will not
/// accept or a missing permission is the server's to fix, and the audio must
/// still be here when it is.
final class IOSChunkTransfer: ChunkTransfer {

    private static let fileFaults = ["BadDigest", "InvalidDigest", "EntityTooLarge", "EntityTooSmall"]

    private let session: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 60
        return URLSession(configuration: configuration)
    }()

    func upload(target: ChunkUploadTarget, path: String) async throws -> TransferResult {
        let file = IOSRecordingFiles.shared.url(path)
        guard FileManager.default.fileExists(atPath: file.path) else { return .missing }
        guard let url = URL(string: target.url) else { return .retry }

        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        for (name, value) in target.headers {
            request.setValue(value, forHTTPHeaderField: name)
        }
        do {
            let (data, response) = try await session.upload(for: request, fromFile: file)
            guard let http = response as? HTTPURLResponse else { return .retry }
            if (200..<300).contains(http.statusCode) { return .uploaded }

            let body = String(data: data, encoding: .utf8) ?? ""
            NSLog("[Recorder] Storage answered %ld for chunk %d: %@",
                  http.statusCode, target.seq, String(body.prefix(300)))
            if http.statusCode == 400, Self.fileFaults.contains(where: { body.contains("<Code>\($0)</Code>") }) {
                return .rejected
            }
            // the link is signed for the row's size and MD5; a file that no longer matches them is
            // refused like this, forever. The sync measures it again and repairs it.
            if http.statusCode == 403, body.contains("<Code>SignatureDoesNotMatch</Code>") {
                return .mismatch
            }
            // an answer, so not "no signal": this recording waits, the others still go.
            return .failed
        } catch {
            return .retry
        }
    }
}

/// Which phone made a recording, for the admin looking at a strange one.
enum RecorderDevices {
    static var current: RecorderDevice {
        var system = utsname()
        uname(&system)
        let model = withUnsafePointer(to: &system.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: 1) { String(cString: $0) }
        }
        return RecorderDevice(
            platform: "ios",
            appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "",
            model: model
        )
    }
}
