import Foundation
import Darwin

struct CopierSettings: Codable {
    var historyLimit: Int = 40
    var pinned: Bool = false
    var theme: String = "light"
    var sizeNoticeSeconds: Int = 4

    private enum CodingKeys: String, CodingKey { case historyLimit, pinned, theme, sizeNoticeSeconds }

    init(historyLimit: Int = 40, pinned: Bool = false, theme: String = "light", sizeNoticeSeconds: Int = 4) {
        self.historyLimit = historyLimit
        self.pinned = pinned
        self.theme = theme
        self.sizeNoticeSeconds = sizeNoticeSeconds
    }

    init(from decoder: Decoder) throws {
        let fields = try decoder.container(keyedBy: CodingKeys.self)
        historyLimit = try fields.decodeIfPresent(Int.self, forKey: .historyLimit) ?? 40
        pinned = try fields.decodeIfPresent(Bool.self, forKey: .pinned) ?? false
        theme = try fields.decodeIfPresent(String.self, forKey: .theme) ?? "light"
        sizeNoticeSeconds = try fields.decodeIfPresent(Int.self, forKey: .sizeNoticeSeconds) ?? 4
    }
}

struct HistorySnapshot {
    let entries: [String]
    let pinned: [String]
}

struct DrawerPosition: Codable {
    var screenX: Double
    var screenY: Double
    var screenWidth: Double
    var screenHeight: Double
    var rightSide: Bool
    var verticalFraction: Double

    var valid: Bool {
        screenX.isFinite && screenY.isFinite && screenWidth.isFinite && screenWidth > 0
            && screenHeight.isFinite && screenHeight > 0
            && verticalFraction.isFinite && (0...1).contains(verticalFraction)
    }
}

struct WindowSize {
    static let factoryWidth = 760
    static let factoryHeight = 340
    static let minWidth = 500
    static let minHeight = 260
    static let maxWidth = 4096
    static let maxHeight = 2160

    var width: Int = factoryWidth
    var height: Int = factoryHeight
    var defaultWidth: Int = factoryWidth
    var defaultHeight: Int = factoryHeight

    var valid: Bool {
        (Self.minWidth...Self.maxWidth).contains(width)
            && (Self.minHeight...Self.maxHeight).contains(height)
            && (Self.minWidth...Self.maxWidth).contains(defaultWidth)
            && (Self.minHeight...Self.maxHeight).contains(defaultHeight)
    }

    func withCurrent(width: Int, height: Int) -> WindowSize {
        WindowSize(width: width, height: height,
                   defaultWidth: defaultWidth, defaultHeight: defaultHeight)
    }

    func savingCurrentAsDefault() -> WindowSize {
        WindowSize(width: width, height: height, defaultWidth: width, defaultHeight: height)
    }

    func resetToDefault() -> WindowSize {
        withCurrent(width: defaultWidth, height: defaultHeight)
    }
}

enum StorageError: Error {
    case invalidFile(String)
    case symbolicLink(String)
}

/// Keeps the Swift app compatible with the Java apps' settings and history files.
final class CopierStorage {
    let directory: URL

    init(directory: URL? = nil) {
        if let directory {
            self.directory = directory
        } else if let override = ProcessInfo.processInfo.environment["NASHCLIP_DATA_DIR"],
                  !override.isEmpty {
            self.directory = URL(fileURLWithPath: override, isDirectory: true)
        } else {
            self.directory = Self.preferredDirectory()
        }
    }

    static func preferredDirectory(
        home: URL = FileManager.default.homeDirectoryForCurrentUser
    ) -> URL {
        let manager = FileManager.default
        let current = home.appendingPathComponent(".nashclip", isDirectory: true)
        let legacy = home.appendingPathComponent(".mcopier", isDirectory: true)
        if manager.fileExists(atPath: current.path) { return current }
        guard let oldAttributes = try? manager.attributesOfItem(atPath: legacy.path),
              oldAttributes[.type] as? FileAttributeType == .typeDirectory else { return current }

        let staging = home.appendingPathComponent(".nashclip-migration-\(UUID().uuidString)",
                                               isDirectory: true)
        defer { try? manager.removeItem(at: staging) }
        do {
            try manager.createDirectory(at: staging, withIntermediateDirectories: false,
                                        attributes: [.posixPermissions: 0o700])
            let names = ["settings.json", "history.properties", "window-size.properties",
                         "java-fx-position.properties", "swift-position.json"]
            for name in names {
                let source = legacy.appendingPathComponent(name)
                guard let attributes = try? manager.attributesOfItem(atPath: source.path),
                      attributes[.type] as? FileAttributeType == .typeRegular else { continue }
                let destination = staging.appendingPathComponent(name)
                try manager.copyItem(at: source, to: destination)
                try manager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: destination.path)
            }
            try manager.moveItem(at: staging, to: current)
            return current
        } catch {
            fputs("Could not migrate clipboard data: \(error)\n", stderr)
            return manager.fileExists(atPath: current.path) ? current : legacy
        }
    }

    func loadSettings() throws -> CopierSettings {
        let file = directory.appendingPathComponent("settings.json")
        if !FileManager.default.fileExists(atPath: file.path) {
            let defaults = CopierSettings()
            try saveSettings(defaults)
            return defaults
        }
        try rejectLink(file)
        let data = try read(file, limit: 65_536)
        let settings = try JSONDecoder().decode(CopierSettings.self, from: data)
        guard (1...HistoryPolicy.maxEntries).contains(settings.historyLimit), ["light", "dark"].contains(settings.theme),
              settings.sizeNoticeSeconds == 0 || (2...5).contains(settings.sizeNoticeSeconds) else {
            throw StorageError.invalidFile("historyLimit must be 1–500, theme light/dark, and sizeNoticeSeconds 0 or 2–5")
        }
        return settings
    }

    func saveSettings(_ settings: CopierSettings) throws {
        guard (1...HistoryPolicy.maxEntries).contains(settings.historyLimit), ["light", "dark"].contains(settings.theme),
              settings.sizeNoticeSeconds == 0 || (2...5).contains(settings.sizeNoticeSeconds) else {
            throw StorageError.invalidFile("historyLimit must be 1–500, theme light/dark, and sizeNoticeSeconds 0 or 2–5")
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        var data = try encoder.encode(settings)
        data.append(0x0A)
        try write(data, named: "settings.json")
    }

    func loadHistory(limit: Int) throws -> [String] { try loadHistorySnapshot(limit: limit).entries }

    func loadHistorySnapshot(limit: Int) throws -> HistorySnapshot {
        let file = directory.appendingPathComponent("history.properties")
        guard FileManager.default.fileExists(atPath: file.path) else { return HistorySnapshot(entries: [], pinned: []) }
        try rejectLink(file)
        let source = try text(file, limit: HistoryPolicy.maxFileBytes)
        let values = try JavaProperties.read(source)
        guard ["1", "2"].contains(values["version"] ?? ""), let countText = values["count"],
              let count = Int(countText), count >= 0 else {
            throw StorageError.invalidFile("Invalid history version or count")
        }
        var pinned: [String] = []
        if values["version"] == "2" {
            guard let countText = values["pinned.count"], let pinCount = Int(countText),
                  (0...HistoryPolicy.maxPinned).contains(pinCount) else {
                throw StorageError.invalidFile("Invalid pinned count")
            }
            for index in 0..<pinCount {
                guard let text = values["pinned.\(index)"], HistoryPolicy.accepts(text),
                      !HistoryPolicy.contains(pinned, text) else { throw StorageError.invalidFile("Invalid pinned clip") }
                pinned.append(text)
            }
        }
        let entries: [String] = try (0..<min(count, min(limit, HistoryPolicy.maxEntries))).map { index in
            guard let value = values["entry.\(index)"] else {
                throw StorageError.invalidFile("Missing history entry \(index)")
            }
            guard value.utf16.count <= HistoryPolicy.maxTextUnits else {
                throw StorageError.invalidFile("Saved clip exceeds safety limit; original file preserved")
            }
            return value
        }
        return HistorySnapshot(entries: try HistoryPolicy.withPins(entries, pinned: pinned, limit: limit), pinned: pinned)
    }

    func saveHistory(_ entries: [String], limit: Int, pinned: [String] = []) throws {
        let saved = try HistoryPolicy.withPins(entries, pinned: pinned, limit: limit).filter { !HistoryPolicy.contains(pinned, $0) }
        var lines = ["version=2", "count=\(saved.count)", "pinned.count=\(pinned.count)"]
        for (index, entry) in pinned.enumerated() {
            lines.append("pinned.\(index)=\(JavaProperties.escape(entry))")
        }
        for (index, entry) in saved.enumerated() {
            lines.append("entry.\(index)=\(JavaProperties.escape(entry))")
        }
        try write(Data((lines.joined(separator: "\n") + "\n").utf8),
                  named: "history.properties")
    }

    func loadPosition() throws -> DrawerPosition? {
        let file = directory.appendingPathComponent("swift-position.json")
        guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        try rejectLink(file)
        let value = try JSONDecoder().decode(DrawerPosition.self, from: read(file, limit: 65_536))
        guard value.valid else {
            throw StorageError.invalidFile("Invalid saved drawer position")
        }
        return value
    }

    func savePosition(_ position: DrawerPosition) throws {
        guard position.valid else { throw StorageError.invalidFile("Invalid drawer position") }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try write(encoder.encode(position), named: "swift-position.json")
    }

    func loadSize() throws -> WindowSize {
        let file = directory.appendingPathComponent("window-size.properties")
        guard FileManager.default.fileExists(atPath: file.path) else { return WindowSize() }
        try rejectLink(file)
        let values = try JavaProperties.read(text(file, limit: 65_536))
        guard let width = values["width"].flatMap(Int.init),
              let height = values["height"].flatMap(Int.init),
              let defaultWidth = values["defaultWidth"].flatMap(Int.init),
              let defaultHeight = values["defaultHeight"].flatMap(Int.init) else {
            throw StorageError.invalidFile("Invalid window size file")
        }
        let size = WindowSize(width: width, height: height,
                              defaultWidth: defaultWidth, defaultHeight: defaultHeight)
        guard size.valid else { throw StorageError.invalidFile("Invalid window size file") }
        return size
    }

    func saveSize(_ size: WindowSize) throws {
        guard size.valid else { throw StorageError.invalidFile("Invalid window size") }
        let contents = "width=\(size.width)\nheight=\(size.height)\n"
            + "defaultWidth=\(size.defaultWidth)\ndefaultHeight=\(size.defaultHeight)\n"
        try write(Data(contents.utf8), named: "window-size.properties")
    }

    private func write(_ data: Data, named name: String) throws {
        let manager = FileManager.default
        try rejectLink(directory)
        try manager.createDirectory(at: directory, withIntermediateDirectories: true,
                                    attributes: [.posixPermissions: 0o700])
        try manager.setAttributes([.posixPermissions: 0o700], ofItemAtPath: directory.path)
        let file = directory.appendingPathComponent(name)
        try rejectLink(file)
        // Create private from the first byte, then atomically replace the destination.
        let temporary = directory.appendingPathComponent("write-\(UUID().uuidString).tmp")
        let descriptor = Darwin.open(temporary.path, O_WRONLY | O_CREAT | O_EXCL, 0o600)
        guard descriptor >= 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
        defer { try? manager.removeItem(at: temporary) }
        let output = FileHandle(fileDescriptor: descriptor, closeOnDealloc: true)
        do {
            try output.write(contentsOf: data)
            try output.close()
        } catch {
            try? output.close()
            throw error
        }
        guard Darwin.rename(temporary.path, file.path) == 0 else {
            throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO)
        }
    }

    private func read(_ file: URL, limit: Int) throws -> Data {
        try rejectLink(directory)
        try rejectLink(file)
        let attributes = try FileManager.default.attributesOfItem(atPath: file.path)
        guard attributes[.type] as? FileAttributeType == .typeRegular else {
            throw StorageError.invalidFile("Expected a regular file")
        }
        let input = try FileHandle(forReadingFrom: file)
        defer { try? input.close() }
        var result = Data()
        while let chunk = try input.read(upToCount: min(65_536, limit + 1 - result.count)), !chunk.isEmpty {
            result.append(chunk)
            if result.count > limit { throw StorageError.invalidFile("File exceeds safety limit") }
        }
        return result
    }

    private func text(_ file: URL, limit: Int) throws -> String {
        guard let value = String(data: try read(file, limit: limit), encoding: .utf8) else {
            throw StorageError.invalidFile("Invalid UTF-8")
        }
        return value
    }

    private func rejectLink(_ url: URL) throws {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: url.path) else { return }
        if attributes[.type] as? FileAttributeType == .typeSymbolicLink {
            throw StorageError.symbolicLink(url.path)
        }
    }
}

/// The subset of Java Properties syntax used by HistoryStore, including escaped text.
enum JavaProperties {
    static func read(_ source: String) throws -> [String: String] {
        var values: [String: String] = [:]
        var current = ""
        var continuing = false
        for physical in source.replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n").components(separatedBy: "\n") {
            if continuing {
                current += String(physical.drop(while: { $0 == " " || $0 == "\t" }))
            } else {
                current = physical
            }
            let slashCount = current.reversed().prefix(while: { $0 == "\\" }).count
            if slashCount % 2 == 1 {
                current.removeLast()
                continuing = true
                continue
            }
            continuing = false
            let line = String(current.drop(while: { $0 == " " || $0 == "\t" }))
            current = ""
            if line.isEmpty || line.hasPrefix("#") || line.hasPrefix("!") { continue }
            guard let split = line.firstIndex(of: "=") else {
                throw StorageError.invalidFile("Invalid Java properties line")
            }
            let key = String(line[..<split]).trimmingCharacters(in: .whitespaces)
            values[key] = try unescape(String(line[line.index(after: split)...]))
        }
        if continuing { throw StorageError.invalidFile("Incomplete history escape") }
        return values
    }

    static func escape(_ value: String) -> String {
        var result = ""
        for (index, character) in value.unicodeScalars.enumerated() {
            switch character {
            case "\\": result += "\\\\"
            case "\n": result += "\\n"
            case "\r": result += "\\r"
            case "\t": result += "\\t"
            case "\u{0C}": result += "\\f"
            case "=", ":", "#", "!": result += "\\\(character)"
            case " " where index == 0: result += "\\ "
            default: result.unicodeScalars.append(character)
            }
        }
        return result
    }

    private static func unescape(_ value: String) throws -> String {
        let units = Array(value.utf16)
        var decoded: [UInt16] = []
        var index = 0
        while index < units.count {
            let unit = units[index]
            if unit != 92 {
                decoded.append(unit)
                index += 1
                continue
            }
            index += 1
            guard index < units.count else {
                throw StorageError.invalidFile("Incomplete history escape")
            }
            switch units[index] {
            case 110: decoded.append(10) // n
            case 114: decoded.append(13) // r
            case 116: decoded.append(9)  // t
            case 102: decoded.append(12) // f
            case 117: // u followed by four hexadecimal digits
                guard index + 4 < units.count else {
                    throw StorageError.invalidFile("Incomplete Unicode escape")
                }
                let digits = String(decoding: units[(index + 1)...(index + 4)], as: UTF16.self)
                guard let number = UInt16(digits, radix: 16) else {
                    throw StorageError.invalidFile("Invalid Unicode escape")
                }
                decoded.append(number)
                index += 4
            default: decoded.append(units[index])
            }
            index += 1
        }
        return String(decoding: decoded, as: UTF16.self)
    }
}
