import Foundation

@main
struct StorageChecks {
    static let sample = ["é", "e\u{0301}", "\u{00a0}", " leading space\r\nnext\tline\\end=:# !", "emoji 😀 👨‍👩‍👧", "a\u{2028}b\u{2029}c", "nul\0end"]
    static func check(_ condition: Bool, _ message: String) {
        precondition(condition, message)
    }
    static func exact(_ a: [String], _ b: [String]) -> Bool {
        a.map { Array($0.utf16) } == b.map { Array($0.utf16) }
    }
    static func rejected(_ action: () throws -> Void) {
        do { try action() } catch { return }
        preconditionFailure("Expected rejection")
    }
    static func main() throws {
        let args = CommandLine.arguments
        let directory = URL(fileURLWithPath: args[2], isDirectory: true)
        let storage = CopierStorage(directory: directory)
        if args[1] == "hold" {
            let lock = try InstanceLock(directory: directory)
            print("LOCKED"); fflush(stdout)
            _ = readLine()
            withExtendedLifetime(lock) { }
            return
        }
        if args[1] == "probe" {
            do { let lock = try InstanceLock(directory: directory); withExtendedLifetime(lock) { print("ACQUIRED") } }
            catch { print("BLOCKED") }
            return
        }
        if args[1] == "emit" { try storage.saveHistory(sample, limit: 40); try storage.saveSettings(CopierSettings(sizeNoticeSeconds: 0)); return }
        if args[1] == "verify" { let values = try storage.loadHistory(limit: 40); check(exact(values, sample), "Cross-language mismatch"); let settings = try storage.loadSettings(); check(settings.sizeNoticeSeconds == 0, "Cross-language notice setting"); return }
        if args[1] == "emitPins" { try storage.saveHistory(sample, limit: 40, pinned: Array(sample.prefix(2))); return }
        if args[1] == "verifyPins" {
            let saved = try storage.loadHistorySnapshot(limit: 40)
            check(exact(saved.entries, sample) && exact(saved.pinned, Array(sample.prefix(2))), "Cross-language pins")
            return
        }
        for seconds in [0, 2, 3, 4, 5] {
            try storage.saveSettings(CopierSettings(sizeNoticeSeconds: seconds))
            let settings = try storage.loadSettings()
            check(settings.sizeNoticeSeconds == seconds, "Notice preference round trip")
        }
        for seconds in [-1, 1, 6] { rejected { try storage.saveSettings(CopierSettings(sizeNoticeSeconds: seconds)) } }
        let maximum = String(repeating: "x", count: HistoryPolicy.maxTextUnits)
        check(!HistoryPolicy.accepts(maximum + "x"), "Oversized text")
        check(HistoryPolicy.bounded(Array(repeating: maximum, count: 500), limit: 500).count == 16, "Total budget")
        check(HistoryPolicy.bounded(Array(repeating: "a", count: 501), limit: 501).count == 500, "Count budget")
        check(!HistoryPolicy.preview(String(repeating: "a", count: 999) + "😀" + maximum).contains("�"), "Split surrogate")
        let fivePins = ["pin1", "pin2", "pin3", "pin4", "pin5"]
        try storage.saveHistory(["new", "old", "pin1"], limit: 1, pinned: fivePins)
        let restoredPins = try storage.loadHistorySnapshot(limit: 1)
        check(restoredPins.pinned == fivePins && restoredPins.entries == fivePins + ["new"], "Pins lost on restart/limit reduction")
        let prior = try Data(contentsOf: directory.appendingPathComponent("history.properties"))
        rejected { try storage.saveHistory([], limit: 1, pinned: fivePins + ["six"]) }
        let after = try Data(contentsOf: directory.appendingPathComponent("history.properties"))
        check(prior == after, "Sixth pin changed saved data")
        rejected { try storage.saveHistory([], limit: 1, pinned: ["same", "same"]) }
        let longPins = (0..<5).map { String($0) + String(maximum.dropFirst()) }
        let budgeted = try HistoryPolicy.withPins(Array(repeating: maximum, count: 500), pinned: longPins, limit: 500)
        check(Array(budgeted.prefix(5)) == longPins && budgeted.reduce(0, { $0 + $1.utf16.count }) <= HistoryPolicy.maxTotalUnits, "Pin memory budget")
        try "version=2\npinned.count=6\ncount=0\n".write(to: directory.appendingPathComponent("history.properties"), atomically: true, encoding: .utf8)
        rejected { _ = try storage.loadHistorySnapshot(limit: 40) }
        try "version=1\ncount=1\nentry.0=legacy\n".write(to: directory.appendingPathComponent("history.properties"), atomically: true, encoding: .utf8)
        let legacy = try storage.loadHistorySnapshot(limit: 40)
        check(legacy.entries == ["legacy"] && legacy.pinned.isEmpty, "Legacy history migration")
        for _ in 0..<100 {
            try storage.saveHistory(sample, limit: 40)
            let values = try storage.loadHistory(limit: 40)
            check(exact(values, sample), "History round trip")
        }
        let attrs = try FileManager.default.attributesOfItem(atPath: directory.appendingPathComponent("history.properties").path)
        check((attrs[.posixPermissions] as? NSNumber)?.intValue == 0o600, "History permissions")
        let names = try FileManager.default.contentsOfDirectory(atPath: directory.path)
        check(!names.contains { $0.hasSuffix(".tmp") }, "Temporary file leak")
        rejected { try storage.saveSettings(CopierSettings(historyLimit: 501)) }
        try Data(repeating: 32, count: 65_537).write(to: directory.appendingPathComponent("settings.json"))
        rejected { _ = try storage.loadSettings() }
        try "version=1\ncount=1\nentry.0=\\uZZZZ\n".write(to: directory.appendingPathComponent("history.properties"), atomically: true, encoding: .utf8)
        rejected { _ = try storage.loadHistory(limit: 40) }
        let oversized = "version=1\ncount=1\nentry.0=" + maximum + "x\n"
        try oversized.write(to: directory.appendingPathComponent("history.properties"), atomically: true, encoding: .utf8)
        rejected { _ = try storage.loadHistory(limit: 40) }
        let preserved = try String(contentsOf: directory.appendingPathComponent("history.properties"), encoding: .utf8)
        check(preserved == oversized, "Oversized saved history changed")
        try Data(repeating: 0, count: HistoryPolicy.maxFileBytes + 1).write(to: directory.appendingPathComponent("history.properties"))
        rejected { _ = try storage.loadHistory(limit: 40) }
        let custom = WindowSize(width: 900, height: 500, defaultWidth: 900, defaultHeight: 500)
        try storage.saveSize(custom.withCurrent(width: 600, height: 300))
        let restored = try storage.loadSize().resetToDefault()
        check(restored.width == 900 && restored.height == 500, "Saved default")
        print("PASS: Swift storage, Unicode, budgets, size, permissions, malformed input")
    }
}
