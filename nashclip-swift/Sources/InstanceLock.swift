import Foundation
import Darwin

/// lockf uses POSIX record locks, interoperable with Java FileChannel.tryLock on macOS.
final class InstanceLock {
    private let descriptor: Int32

    init(directory: URL) throws {
        let manager = FileManager.default
        if let attributes = try? manager.attributesOfItem(atPath: directory.path),
           attributes[.type] as? FileAttributeType == .typeSymbolicLink {
            throw StorageError.symbolicLink(directory.path)
        }
        try manager.createDirectory(at: directory, withIntermediateDirectories: true,
                                    attributes: [.posixPermissions: 0o700])
        let file = directory.appendingPathComponent("instance.lock")
        let fd = Darwin.open(file.path, O_CREAT | O_WRONLY | O_NOFOLLOW | O_CLOEXEC, 0o600)
        guard fd >= 0 else { throw StorageError.invalidFile("Cannot open nashclip instance lock") }
        guard lockf(fd, F_TLOCK, 0) == 0 else {
            Darwin.close(fd)
            throw StorageError.invalidFile("Another nashclip version may be running. Quit it first.")
        }
        descriptor = fd
    }

    deinit { Darwin.close(descriptor) }
}
