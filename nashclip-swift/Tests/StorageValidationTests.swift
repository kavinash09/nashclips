import Foundation

@main struct StorageValidationTests {
    static func main() throws {
        let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        func store() throws -> CopierStorage {
            let s = CopierStorage(directory: root.appendingPathComponent(UUID().uuidString))
            try FileManager.default.createDirectory(at: s.directory, withIntermediateDirectories: true)
            return s
        }
        TestSupport.run("swift.storage.malformed-history.preserved") {
            let five = ["p1", "p2", "p3", "p4", "p5"]
            let allowed = try HistoryPolicy.withPins([], pinned: five, limit: 1)
            try TestSupport.check(TestSupport.exact(allowed, five), "Five pins rejected")
            try TestSupport.rejects { _ = try HistoryPolicy.withPins([], pinned: five + ["p6"], limit: 1) }
            let s = try store(), file = s.directory.appendingPathComponent("history.properties")
            for bad in ["", "version=3\ncount=0", "version=1", "version=1\ncount=-1",
                        "version=1\ncount=no", "version=1\ncount=1", "version=2\ncount=0",
                        "version=2\ncount=0\npinned.count=no", "version=2\ncount=0\npinned.count=-1",
                        "version=2\ncount=0\npinned.count=6", "version=2\ncount=0\npinned.count=1",
                        "version=2\ncount=0\npinned.count=1\npinned.0=   ",
                        "version=2\ncount=0\npinned.count=2\npinned.0=a\npinned.1=a",
                        "version=1\ncount=1\nentry.0=" + String(repeating: "a", count: 65537)] {
                let bytes = Data(bad.utf8); try bytes.write(to: file)
                try TestSupport.rejects { _ = try s.loadHistorySnapshot(limit: 40) }
                let remaining = try Data(contentsOf: file)
                try TestSupport.check(remaining == bytes, "Invalid history overwritten")
            }
        }
        TestSupport.run("swift.properties.escapes.continuation.invalid-utf8") {
            let values = try JavaProperties.read("# comment\r\n! comment\nkey=a\\\n  b\\\r\n\tc\nemoji=\\uD83D\\uDE00\ncontrols=\\n\\r\\t\\f\\\\\\=\\ ")
            try TestSupport.check(values["key"] == "abc" && values["emoji"] == "😀", "Continuation/Unicode decoding")
            try TestSupport.check(values["controls"] == "\n\r\t\u{0C}\\= ", "Control escapes")
            for bad in ["missing separator", "key=\\u12", "key=\\uZZZZ", "key=value\\"] {
                try TestSupport.rejects { _ = try JavaProperties.read(bad) }
            }
            let s = try store(), file = s.directory.appendingPathComponent("history.properties")
            try Data([0xFF, 0xFE]).write(to: file)
            try TestSupport.rejects { _ = try s.loadHistory(limit: 40) }
        }
        TestSupport.run("swift.storage.symlinks-and-nonregular-files") {
            let s = try store(), target = s.directory.appendingPathComponent("target")
            try Data("untouched".utf8).write(to: target)
            for name in ["settings.json", "history.properties", "window-size.properties", "swift-position.json"] {
                try FileManager.default.createSymbolicLink(at: s.directory.appendingPathComponent(name), withDestinationURL: target)
            }
            try TestSupport.rejects { _ = try s.loadSettings() }
            try TestSupport.rejects { try s.saveSettings(CopierSettings()) }
            try TestSupport.rejects { _ = try s.loadHistory(limit: 40) }
            try TestSupport.rejects { try s.saveHistory(["new"], limit: 40) }
            try TestSupport.rejects { _ = try s.loadSize() }
            try TestSupport.rejects { try s.saveSize(WindowSize()) }
            try TestSupport.rejects { _ = try s.loadPosition() }
            let unchanged = try Data(contentsOf: target)
            try TestSupport.check(unchanged == Data("untouched".utf8), "Link target changed")
            let other = try store()
            try FileManager.default.createDirectory(at: other.directory.appendingPathComponent("history.properties"), withIntermediateDirectories: false)
            try TestSupport.rejects { _ = try other.loadHistory(limit: 40) }
        }
        TestSupport.run("swift.settings-and-size.limits-and-preserved-failed-save") {
            let s = try store(); try s.saveSettings(CopierSettings())
            let file = s.directory.appendingPathComponent("settings.json"), before = try Data(contentsOf: file)
            for limit in [0, -1, 501] { try TestSupport.rejects { try s.saveSettings(CopierSettings(historyLimit: limit)) } }
            for seconds in [-1, 1, 6] { try TestSupport.rejects { try s.saveSettings(CopierSettings(sizeNoticeSeconds: seconds)) } }
            try TestSupport.rejects { try s.saveSettings(CopierSettings(theme: "blue")) }
            let after = try Data(contentsOf: file); try TestSupport.check(after == before, "Failed settings save lost old file")
            let size = s.directory.appendingPathComponent("window-size.properties")
            for bad in ["width=760", "width=0\nheight=340\ndefaultWidth=760\ndefaultHeight=340",
                        "width=760\nheight=340\ndefaultWidth=760\ndefaultHeight=2161"] {
                try Data(bad.utf8).write(to: size)
                try TestSupport.rejects { _ = try s.loadSize() }
            }
            try Data(repeating: 32, count: 65537).write(to: file)
            try TestSupport.rejects { _ = try s.loadSettings() }
        }
        TestSupport.run("swift.position.invalid-save-preserves-valid-position") {
            let s = try store()
            let valid = DrawerPosition(screenX: -100, screenY: 0, screenWidth: 1920, screenHeight: 1080, rightSide: true, verticalFraction: 0.5)
            try s.savePosition(valid)
            let file = s.directory.appendingPathComponent("swift-position.json"), before = try Data(contentsOf: file)
            for bad in [0.0, -1.0] {
                var value = valid; value.screenWidth = bad
                try TestSupport.rejects { try s.savePosition(value) }
            }
            for bad in [-0.1, 1.1] {
                var value = valid; value.verticalFraction = bad
                try TestSupport.rejects { try s.savePosition(value) }
            }
            let after = try Data(contentsOf: file); try TestSupport.check(before == after, "Invalid position destroyed good saved data")
        }
        try TestSupport.finish()
    }
}
