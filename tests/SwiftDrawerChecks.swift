import AppKit
import SwiftUI

@main
struct SwiftDrawerChecks {
    @MainActor static func waitUntil(_ message: String, _ condition: () -> Bool) {
        let deadline = Date(timeIntervalSinceNow: 3)
        while !condition(), Date() < deadline { RunLoop.current.run(until: Date(timeIntervalSinceNow: 0.02)) }
        precondition(condition(), message)
    }
    @MainActor static func main() throws {
        _ = NSApplication.shared
        NSApp.setActivationPolicy(.accessory)
        let storage = CopierStorage(directory: URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true))
        let board = NSPasteboard.withUniqueName()
        defer { board.releaseGlobally() }
        let model = ClipboardModel(storage: storage, pasteboard: board)
        for _ in 0..<12 {
            let layout = WindowLayoutState(width: 760, height: 340)
            let view = CopierView(model: model, windowLayout: layout, useClip: { _ in }, deleteClip: { _ in },
                                  togglePin: {}, toggleTheme: {}, hideWindow: {}, quitApp: {}, changeSize: {},
                                  dragHeader: { _, _ in }, dragResize: { _, _, _ in })
            var drawer: DrawerController? = DrawerController(view: view, windowLayout: layout, size: WindowSize(),
                pinned: false, dark: false, savedPosition: nil, onMoved: { _ in }, onResized: { _, _ in })
            weak var released = drawer
            drawer!.panel.alphaValue = 0
            drawer!.handle.alphaValue = 0
            drawer!.panel.ignoresMouseEvents = true
            drawer!.handle.ignoresMouseEvents = true
            drawer!.show()
            RunLoop.current.run(until: Date(timeIntervalSinceNow: 0.4))
            precondition(drawer!.panel.isVisible)
            drawer!.click(at: NSPoint(x: -100000, y: -100000))
            waitUntil("Outside click did not close drawer") { !drawer!.panel.isVisible }
            drawer!.show()
            RunLoop.current.run(until: Date(timeIntervalSinceNow: 0.4))
            drawer!.click(at: NSPoint(x: drawer!.panel.frame.midX, y: drawer!.panel.frame.midY))
            drawer!.click(at: NSPoint(x: -100000, y: -100000))
            RunLoop.current.run(until: Date(timeIntervalSinceNow: 0.5))
            precondition(drawer!.panel.isVisible, "Click-inside latch lost after animation settled")
            var completions = 0
            precondition(drawer!.hide { completions += 1 })
            drawer!.resize(width: 650, height: 300)
            waitUntil("Resize stranded close completion") { completions == 1 && !drawer!.panel.isVisible }
            drawer!.show()
            let edge = NSPoint(x: drawer!.panel.frame.midX, y: drawer!.panel.frame.maxY)
            drawer!.dragResize(edge: .top, to: edge, ended: false)
            precondition(!drawer!.hide { completions += 1 })
            drawer!.dragResize(edge: .top, to: edge, ended: true)
            drawer!.hide { completions += 1 }
            drawer!.close(); drawer!.close(); drawer!.show()
            drawer = nil
            waitUntil("Drawer retained after close") { released == nil }
            precondition(completions == 1, "Stale paste completion after close")
            precondition(released == nil, "Drawer retained after close")
            released = nil
        }
        print("PASS: 12 Swift drawer lifecycles, outside/inside click behavior, resize-during-hide, busy refusal, no stale completion; controllers released")
    }
}
