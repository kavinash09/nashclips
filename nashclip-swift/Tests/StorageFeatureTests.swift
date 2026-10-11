import Foundation

@main struct StorageFeatureTests {
    static func main() throws {
        let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        func store() -> CopierStorage { CopierStorage(directory: root.appendingPathComponent(UUID().uuidString)) }
        TestSupport.run("swift.settings.defaults.roundtrip.theme.pin.notice") {
            let s = store(); let initial = try s.loadSettings()
            try TestSupport.check(initial.historyLimit == 40 && initial.theme == "light" && !initial.pinned && initial.sizeNoticeSeconds == 4, "Defaults")
            for theme in ["light", "dark"] {
                try s.saveSettings(CopierSettings(historyLimit: 12, pinned: true, theme: theme, sizeNoticeSeconds: 0))
                let value = try s.loadSettings()
                try TestSupport.check(value.historyLimit == 12 && value.pinned && value.theme == theme && value.sizeNoticeSeconds == 0, "Settings lost")
            }
        }
        TestSupport.run("swift.settings.reject-invalid.preserve-file") {
            let s = store(); _ = try s.loadSettings()
            let file = s.directory.appendingPathComponent("settings.json")
            for bad in ["[]", "null", "{", "{\"theme\":\"blue\"}", "{\"pinned\":1}", "{\"historyLimit\":-1}", "{\"historyLimit\":1.5}", "{\"sizeNoticeSeconds\":1}"] {
                try bad.write(to: file, atomically: true, encoding: .utf8)
                try TestSupport.rejects { _ = try s.loadSettings() }
                let unchanged = try String(contentsOf: file, encoding: .utf8)
                try TestSupport.check(unchanged == bad, "Corrupt settings overwritten")
            }
        }
        TestSupport.run("swift.history.exact-unicode.whitespace.boundary") {
            for blank in ["", " \n\t", "\u{2003}"] { try TestSupport.check(!HistoryPolicy.accepts(blank), "Blank accepted") }
            for good in ["\u{00a0}", "😀", String(repeating: "x", count: 65536)] { try TestSupport.check(HistoryPolicy.accepts(good), "Valid rejected") }
            try TestSupport.check(!HistoryPolicy.same("é", "e\u{0301}"), "Canonical spellings merged")
            let s = store(); let pins = ["é", "e\u{0301}"]
            let retained = try HistoryPolicy.withPins(["new", "old"], pinned: pins, limit: 1)
            try TestSupport.check(TestSupport.exact(retained, pins + ["new"]), "In-memory history exceeded ordinary limit")
            try s.saveHistory(["new", "old"], limit: 1, pinned: pins)
            let snapshot = try s.loadHistorySnapshot(limit: 1)
            try TestSupport.check(TestSupport.exact(snapshot.entries, pins + ["new"]), "Pins/order/limit")
        }
        TestSupport.run("swift.history.failed-write.preserves-data") {
            let s = store(); let file = s.directory.appendingPathComponent("history.properties")
            try FileManager.default.createDirectory(at: file, withIntermediateDirectories: true)
            let sentinel = file.appendingPathComponent("keep"); try Data("keep".utf8).write(to: sentinel)
            try TestSupport.rejects { try s.saveHistory(["new"], limit: 40) }
            let content = try Data(contentsOf: sentinel); try TestSupport.check(content == Data("keep".utf8), "Failed write changed data")
        }
        TestSupport.run("swift.size.defaults.save.reset.bounds") {
            let s = store(); let initial = try s.loadSize(); try TestSupport.check(initial.width == 760 && initial.height == 340, "Factory")
            let saved = initial.withCurrent(width: 500, height: 260).savingCurrentAsDefault()
            try s.saveSize(saved.withCurrent(width: 4096, height: 2160))
            let reset = try s.loadSize().resetToDefault(); try TestSupport.check(reset.width == 500 && reset.height == 260, "Reset")
            try TestSupport.rejects { try s.saveSize(saved.withCurrent(width: 499, height: 260)) }
        }
        TestSupport.run("swift.position.both-sides.negative-screen.invalid-input") {
            let s = store(); let absent = try s.loadPosition(); try TestSupport.check(absent == nil, "Unexpected position")
            for side in [false, true] {
                let position = DrawerPosition(screenX: -1920, screenY: -200, screenWidth: 1920, screenHeight: 1080, rightSide: side, verticalFraction: 0.8)
                try s.savePosition(position); let actual = try s.loadPosition()!
                try TestSupport.check(actual.rightSide == side && actual.screenX == -1920 && actual.verticalFraction == 0.8, "Position lost")
            }
            try "{\"screenX\":0,\"screenY\":0,\"screenWidth\":0,\"screenHeight\":1080,\"rightSide\":true,\"verticalFraction\":2}".write(to: s.directory.appendingPathComponent("swift-position.json"), atomically: true, encoding: .utf8)
            try TestSupport.rejects { _ = try s.loadPosition() }
        }
        TestSupport.run("swift.migration.copy-once.keeps-legacy") {
            let home = root.appendingPathComponent("migration"); let legacy = home.appendingPathComponent(".mcopier")
            try FileManager.default.createDirectory(at: legacy, withIntermediateDirectories: true)
            try Data("legacy".utf8).write(to: legacy.appendingPathComponent("history.properties"))
            let current = CopierStorage.preferredDirectory(home: home)
            let value = try String(contentsOf: current.appendingPathComponent("history.properties"), encoding: .utf8)
            try TestSupport.check(value == "legacy" && FileManager.default.fileExists(atPath: legacy.path), "Migration")
            try Data("new".utf8).write(to: current.appendingPathComponent("history.properties"))
            _ = CopierStorage.preferredDirectory(home: home)
            let retained = try String(contentsOf: current.appendingPathComponent("history.properties"), encoding: .utf8)
            try TestSupport.check(retained == "new", "Repeated migration overwrote history")
        }
        try TestSupport.finish()
    }
}
