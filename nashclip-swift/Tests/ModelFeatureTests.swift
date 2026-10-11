import AppKit
import SwiftUI

@main struct ModelFeatureTests {
    @MainActor static func main() throws {
        _ = NSApplication.shared; NSApp.setActivationPolicy(.accessory)
        let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
        func fixture(_ action: (ClipboardModel, NSPasteboard, CopierStorage) throws -> Void) throws {
            let board = NSPasteboard.withUniqueName(); defer { board.releaseGlobally() }
            let store = CopierStorage(directory: root.appendingPathComponent(UUID().uuidString))
            let model = ClipboardModel(storage: store, pasteboard: board); model.start(); defer { model.stop() }
            try action(model, board, store)
        }
        func capture(_ model: ClipboardModel, _ board: NSPasteboard, _ text: String) {
            board.clearContents(); board.setString(text, forType: .string); model.checkClipboard()
        }
        TestSupport.run("swift.capture.order.blank.dedup.own-copy.full-text") {
            try fixture { m,b,_ in
                capture(m,b,"one"); capture(m,b,"two"); capture(m,b,"two"); capture(m,b," \n")
                try TestSupport.check(m.clips.map(\.text) == ["two","one"], "Capture rules")
                let full = String(repeating:"x", count: 1200); try TestSupport.check(m.copy(full),"Copy failed"); m.checkClipboard()
                try TestSupport.check(b.string(forType:.string) == full && m.clips.count == 2,"Own copy/preview truncation")
            }
        }
        TestSupport.run("swift.capture.pause-resume.stop") {
            try fixture { m,b,_ in
                capture(m,b,"one");m.togglePaused();capture(m,b,"secret");m.togglePaused();m.checkClipboard()
                try TestSupport.check(m.clips.count == 1,"Paused content retained")
                capture(m,b,"two");m.stop();capture(m,b,"stopped");try TestSupport.check(m.clips.count == 2,"Stopped callback")
            }
        }
        TestSupport.run("swift.size-notice.metadata-only.dismiss") {
            try fixture { m,b,_ in
                capture(m,b,String(repeating:"x",count:65537));try TestSupport.check(m.clips.isEmpty && m.rejectedClip?.units == 65537,"Oversize policy")
                m.dismissSizeNotice();try TestSupport.check(m.rejectedClip == nil,"Dismiss")
            }
        }
        TestSupport.run("swift.pins.five.limit.recopy.restart.unpin.delete") {
            try fixture { m,b,s in
                for i in 0..<6 {capture(m,b,"clip\(i)")}
                for clip in Array(m.clips.prefix(5)) {m.toggleClipPin(clip.id)}
                let unpinned = m.clips.first{!m.isPinned($0)}!;m.toggleClipPin(unpinned.id)
                try TestSupport.check(m.pinnedTexts.count == 5,"Sixth pin accepted")
                capture(m,b,m.pinnedTexts[0]);try TestSupport.check(m.clips.count == 6,"Pinned recopy duplicated")
                let restarted = ClipboardModel(storage:s,pasteboard:b)
                try TestSupport.check(TestSupport.exact(restarted.pinnedTexts,m.pinnedTexts),"Pins lost at restart")
                m.toggleClipPin(m.clips[0].id);try TestSupport.check(m.pinnedTexts.count == 4,"Unpin")
                let pinned=m.clips.first{m.isPinned($0)}!;m.delete(pinned.id)
                try TestSupport.check(m.pinnedTexts.count == 3 && !m.clips.contains{$0.id == pinned.id},"Delete pinned")
            }
        }
        TestSupport.run("swift.history.clear-preserves-pins-and-clipboard.limit") {
            try fixture { m,b,_ in
                capture(m,b,"pin");m.toggleClipPin(m.clips[0].id)
                for i in 0..<4 {capture(m,b,"clip\(i)")}
                try m.updateSettings(limit:1,pinned:false,theme:"light")
                try TestSupport.check(m.clips.count == 2 && m.clips[0].text == "pin","Ordinary limit evicted pin")
                let before=b.string(forType:.string);m.clearHistory()
                try TestSupport.check(m.clips.map(\.text) == ["pin"] && b.string(forType:.string) == before,"Clear semantics")
            }
        }
        TestSupport.run("swift.history.failed-pin-delete-clear-preserves-model") {
            try fixture { m,b,s in
                capture(m,b,"keep");let clip=m.clips[0];let file=s.directory.appendingPathComponent("history.properties")
                try FileManager.default.removeItem(at:file);try FileManager.default.createDirectory(at:file,withIntermediateDirectories:false)
                try Data("block".utf8).write(to:file.appendingPathComponent("block"))
                m.toggleClipPin(clip.id);m.delete(clip.id);m.clearHistory()
                try TestSupport.check(m.clips.map(\.text) == ["keep"] && m.pinnedTexts.isEmpty,"Failed write changed model")
            }
        }
        TestSupport.run("swift.settings.theme.drawer-pin.notice-preservation") {
            try fixture { m,_,s in
                try s.saveSettings(CopierSettings(sizeNoticeSeconds:0));try m.updateTheme("dark");try m.updatePinned(true)
                let saved=try s.loadSettings();try TestSupport.check(saved.theme == "dark" && saved.pinned && saved.sizeNoticeSeconds == 0,"Settings overwritten")
                try m.updateTheme("light");try TestSupport.check(m.settings.theme == "light","Theme reset")
            }
        }
        TestSupport.run("swift.history.corrupt-file.clear-protection") {
            let s=CopierStorage(directory:root.appendingPathComponent(UUID().uuidString));_ = try s.loadSettings()
            let file=s.directory.appendingPathComponent("history.properties");try Data("bad".utf8).write(to:file)
            let b=NSPasteboard.withUniqueName();defer{b.releaseGlobally()};let m=ClipboardModel(storage:s,pasteboard:b);m.clearHistory()
            let retained=try Data(contentsOf:file);try TestSupport.check(retained == Data("bad".utf8),"Clear overwrote corrupt source")
        }
        TestSupport.run("swift.paste.missing-target.fallback-and-stop") {
            let paste=PasteCoordinator();var calls=0
            paste.paste(to:nil,expectedText:"unused",clipboardChange:0){sent,_ in if !sent{calls += 1}}
            paste.paste(to:PasteCoordinator.Target(pid:getpid(),name:"test",focusedWindow:nil),expectedText:"unused",clipboardChange:0){sent,_ in if !sent{calls += 1}}
            paste.stop();paste.stop();try TestSupport.check(calls == 2 && paste.snapshot() == nil,"Fallback or cleanup")
        }
        try TestSupport.finish()
    }
}
