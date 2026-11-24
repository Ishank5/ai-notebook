import CryptoKit
import Foundation
import shared

/// The recorder's folder: `Application Support/Recorder`, the same folder
/// `recordings.db` lives in, kept out of iCloud backup by the shared driver
/// factory that creates it.
///
/// EVERY PATH IS RELATIVE TO IT. iOS moves an app's container when the app is
/// updated, so the queue stores `id/00003.aac` and this resolves it against
/// wherever the folder is today.
///
/// NOTHING HERE THROWS INTO KOTLIN. The shared sync calls these through a
/// Kotlin interface, and an error crossing that boundary arrives as whatever
/// the bridge makes of it — so a failure is an answer (`nil`), never a throw.
final class IOSRecordingFiles: RecordingFiles {

    static let shared = IOSRecordingFiles()

    let root: URL

    private init() {
        root = URL(
            fileURLWithPath: RecordingDriverFactory.companion.recorderFolder(),
            isDirectory: true
        )
    }

    func url(_ path: String) -> URL {
        root.appendingPathComponent(path)
    }

    func freeBytes() -> Int64 {
        let values = try? root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return values?.volumeAvailableCapacityForImportantUsage ?? Int64.max
    }

    /// nil when it cannot be read just now (locked until the first unlock after a restart) as well
    /// as when it is missing; `exists` tells the shared code which, so a locked file is never deleted.
    func read(path: String) async throws -> KotlinByteArray? {
        guard let data = try? Data(contentsOf: url(path)) else { return nil }
        return data.kotlinBytes()
    }

    func exists(path: String) async throws -> KotlinBoolean {
        KotlinBoolean(bool: FileManager.default.fileExists(atPath: url(path).path))
    }

    /// the file's length, or nil when it cannot be had.
    func size(path: String) async throws -> KotlinLong? {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: url(path).path),
              let size = attributes[.size] as? NSNumber
        else { return nil }
        return KotlinLong(value: size.int64Value)
    }

    func seal(path: String, length: Int64) async throws -> String? {
        guard let handle = try? FileHandle(forUpdating: url(path)) else { return nil }
        defer { try? handle.close() }
        do {
            let size = try handle.seekToEnd()
            if size > UInt64(length) { try handle.truncate(atOffset: UInt64(length)) }
            try handle.synchronize()
            try handle.seek(toOffset: 0)
            var hasher = Insecure.MD5()
            while let block = try handle.read(upToCount: 64 * 1024), !block.isEmpty {
                hasher.update(data: block)
            }
            return Data(hasher.finalize()).base64EncodedString()
        } catch {
            return nil
        }
    }

    func delete(path: String) async throws {
        try? FileManager.default.removeItem(at: url(path))
    }

    /// set aside in `rejected/`, never deleted.
    func reject(path: String) async throws -> KotlinBoolean {
        let from = url(path)
        guard FileManager.default.fileExists(atPath: from.path) else { return KotlinBoolean(bool: true) }
        let to = root.appendingPathComponent("rejected").appendingPathComponent(path)
        do {
            try FileManager.default.createDirectory(at: to.deletingLastPathComponent(), withIntermediateDirectories: true)
            if FileManager.default.fileExists(atPath: to.path) { try FileManager.default.removeItem(at: to) }
            try FileManager.default.moveItem(at: from, to: to)
            return KotlinBoolean(bool: true)
        } catch {
            return KotlinBoolean(bool: false)
        }
    }

    func deleteFolder(folder: String) async throws {
        try? FileManager.default.removeItem(at: url(folder))
    }
}

/// One chunk file, being written.
///
/// Each frame goes straight to the file descriptor, and [sync] pushes it to
/// flash every few seconds, so a flat battery loses at most those seconds.
/// The MD5 is kept as the bytes go by, so closing does not read the file back.
final class IOSChunkWriter {

    let seq: Int32
    let path: String
    private let handle: FileHandle
    private var hasher = Insecure.MD5()
    private(set) var bytes: Int64 = 0
    private(set) var frames: Int64 = 0

    struct Closed {
        let bytes: Int64
        let frames: Int64
        let md5: String
    }

    init(seq: Int32, path: String, url: URL) throws {
        self.seq = seq
        self.path = path
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(), withIntermediateDirectories: true
        )
        // Created empty, replacing anything under this name: a leftover file
        // is not this chunk.
        FileManager.default.createFile(atPath: url.path, contents: nil)
        handle = try FileHandle(forWritingTo: url)
    }

    func write(_ frame: Data) throws {
        try handle.write(contentsOf: frame)
        hasher.update(data: frame)
        bytes += Int64(frame.count)
        frames += 1
    }

    func sync() throws {
        try handle.synchronize()
    }

    func close() throws -> Closed {
        try handle.synchronize()
        // fsync on Apple's systems reaches the drive, not its cache; F_FULLFSYNC makes the closed
        // minute survive a power cut. Once a chunk, so its cost (tens of milliseconds) does not matter.
        if fcntl(handle.fileDescriptor, F_FULLFSYNC) != 0 {
            NSLog("[Recorder] F_FULLFSYNC failed for chunk %d (errno %d)", seq, errno)
        }
        try handle.close()
        return Closed(bytes: bytes, frames: frames, md5: Data(hasher.finalize()).base64EncodedString())
    }

    /// Lets go without a verdict; recovery seals whatever reached the disk.
    func abandon() {
        try? handle.close()
    }
}

extension Data {

    /// Kotlin's bytes as Swift's. Byte by byte, which is fine for the seven
    /// of an ADTS header and for the one chunk a repair reads.
    init(kotlin bytes: KotlinByteArray) {
        var array = [UInt8](repeating: 0, count: Int(bytes.size))
        for i in array.indices {
            array[i] = UInt8(bitPattern: bytes.get(index: Int32(i)))
        }
        self.init(array)
    }

    func kotlinBytes() -> KotlinByteArray {
        let array = KotlinByteArray(size: Int32(count))
        for (i, byte) in enumerated() {
            array.set(index: Int32(i), value: Int8(bitPattern: byte))
        }
        return array
    }
}
