import Foundation

/// Keep these budgets aligned with Java's HistoryPolicy.
enum HistoryPolicy {
    static let maxPinned = 5
    static let maxEntries = 500
    static let maxTextUnits = 65_536
    static let maxTotalUnits = 1_048_576
    static let maxFileBytes = 16 * 1024 * 1024

    static func accepts(_ text: String) -> Bool {
        text.utf16.count <= maxTextUnits && !text.unicodeScalars.allSatisfy { scalar in
            switch scalar.value {
            case 0x09...0x0D, 0x1C...0x1F: return true
            case 0x00A0, 0x2007, 0x202F: return false
            default:
                return [.spaceSeparator, .lineSeparator, .paragraphSeparator].contains(scalar.properties.generalCategory)
            }
        }
    }

    static func same(_ a: String, _ b: String) -> Bool { a.utf16.elementsEqual(b.utf16) }
    static func contains(_ values: [String], _ text: String) -> Bool { values.contains { same($0, text) } }

    static func preview(_ text: String) -> String {
        guard text.utf16.count > 1000 else { return text }
        var units = Array(text.utf16.prefix(1000))
        if let last = units.last, (0xD800...0xDBFF).contains(last) { units.removeLast() }
        return String(decoding: units, as: UTF16.self) + "\n… (Use copies the complete text)"
    }

    static func withPins(_ entries: [String], pinned: [String], limit: Int) throws -> [String] {
        guard pinned.count <= maxPinned, Set(pinned.map { Data($0.utf8) }).count == pinned.count,
              pinned.allSatisfy(accepts) else { throw StorageError.invalidFile("At most five distinct clips may be pinned") }
        var result = pinned
        var total = pinned.reduce(0) { $0 + $1.utf16.count }
        var regular = 0
        for text in entries {
            if contains(pinned, text) || !accepts(text) { continue }
            let length = text.utf16.count
            if regular >= min(limit, maxEntries) || total + length > maxTotalUnits { break }
            result.append(text)
            total += length
            regular += 1
        }
        return result
    }

    static func bounded(_ entries: [String], limit: Int) -> [String] {
        var result: [String] = []
        var total = 0
        for text in entries {
            guard accepts(text) else { continue }
            let length = text.utf16.count
            if result.count >= min(limit, maxEntries) || total + length > maxTotalUnits { break }
            result.append(text)
            total += length
        }
        return result
    }
}
