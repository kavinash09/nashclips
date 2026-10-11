import AppKit
import Foundation

/// Uses a private named pasteboard; never reads or changes the system clipboard.
@main
struct SwiftLifecycleChecks {
    @MainActor static func main() throws {
        let directory = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
        let storage = CopierStorage(directory: directory)
        let board = NSPasteboard.withUniqueName()
        defer { board.releaseGlobally() }
        for _ in 0..<200 {
            var model: ClipboardModel? = ClipboardModel(storage: storage, pasteboard: board)
            weak var weakModel = model
            model!.start()
            let firstTimer = timer(model!)!
            model!.start()
            precondition(timer(model!) === firstTimer, "Second start registered another timer")
            model!.stop()
            model!.stop()
            precondition(!firstTimer.isValid, "Stop left a timer registered")
            model!.start()
            let lastTimer = timer(model!)!
            model = nil
            precondition(weakModel == nil, "ClipboardModel retained after release")
            weakModel = nil
            precondition(!lastTimer.isValid, "Deinit left a timer registered")
        }
        let model = ClipboardModel(storage: storage, pasteboard: board)
        model.start()
        board.clearContents()
        board.setString("audit clip", forType: .string)
        RunLoop.current.run(until: Date(timeIntervalSinceNow: 1.2))
        precondition(model.clips.first?.text == "audit clip")
        model.togglePaused()
        board.clearContents()
        board.setString("private while paused", forType: .string)
        RunLoop.current.run(until: Date(timeIntervalSinceNow: 1.2))
        precondition(model.clips.count == 1)
        model.togglePaused()
        RunLoop.current.run(until: Date(timeIntervalSinceNow: 1.2))
        precondition(model.clips.count == 1, "Resume captured content copied while paused")
        board.clearContents()
        board.setString(String(repeating: "x", count: HistoryPolicy.maxTextUnits + 1), forType: .string)
        RunLoop.current.run(until: Date(timeIntervalSinceNow: 1.2))
        precondition(model.rejectedClip?.units == HistoryPolicy.maxTextUnits + 1)
        precondition(model.clips.count == 1, "Rejected text entered history")
        model.dismissSizeNotice()
        RunLoop.current.run(until: Date(timeIntervalSinceNow: 1.2))
        precondition(model.rejectedClip == nil, "Dismissed notice returned on another poll")
        model.stop()
        try storage.saveHistory(["1", "2", "3", "4", "5", "6"], limit: 40)
        let pinsModel = ClipboardModel(storage: storage, pasteboard: board)
        let ids = pinsModel.clips.map(\.id)
        for id in ids.prefix(5) { pinsModel.toggleClipPin(id) }
        precondition(pinsModel.pinnedTexts.count == 5)
        pinsModel.toggleClipPin(ids[5])
        precondition(pinsModel.pinnedTexts.count == 5 && !pinsModel.pinnedTexts.contains("6"), "Sixth pin accepted")
        let reopened = ClipboardModel(storage: storage, pasteboard: board)
        precondition(reopened.pinnedTexts == pinsModel.pinnedTexts, "Pins not restored")
        try pinsModel.updateSettings(limit: 1, pinned: false, theme: "light")
        precondition(pinsModel.pinnedTexts.count == 5 && pinsModel.clips.count == 6)
        pinsModel.toggleClipPin(ids[0])
        pinsModel.toggleClipPin(ids[0])
        precondition(pinsModel.pinnedTexts.count == 5, "Unpin/repin failed")
        let historyFile = directory.appendingPathComponent("history.properties")
        let backup = directory.appendingPathComponent("backup.properties")
        try FileManager.default.moveItem(at: historyFile, to: backup)
        try FileManager.default.createDirectory(at: historyFile, withIntermediateDirectories: false)
        pinsModel.toggleClipPin(ids[0])
        precondition(pinsModel.pinnedTexts.count == 5, "Failed save changed pin state")
        try FileManager.default.removeItem(at: historyFile)
        try FileManager.default.moveItem(at: backup, to: historyFile)
        pinsModel.clearHistory()
        precondition(pinsModel.clips.count == 5 && pinsModel.pinnedTexts.count == 5, "Clear removed pins")
        pinsModel.delete(ids[0])
        precondition(pinsModel.clips.count == 4 && pinsModel.pinnedTexts.count == 4, "Pinned deletion failed")
        let final = ClipboardModel(storage: storage, pasteboard: board)
        precondition(final.clips.count == 4 && final.pinnedTexts.count == 4, "Pin deletion not persisted")
        print("PASS: 200 Swift timer start/start/stop/stop/restart/deinit cycles; private-pasteboard capture and pause")
    }
    @MainActor static func timer(_ model: ClipboardModel) -> Timer? {
        Mirror(reflecting: model).children.first { $0.label == "timer" }?.value as? Timer
    }
}
