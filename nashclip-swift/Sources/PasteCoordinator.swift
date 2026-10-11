import AppKit
import ApplicationServices
import Carbon

/// Tracks the previous app and its focused window, then sends Command + V.
@MainActor
final class PasteCoordinator {
    struct Target {
        let pid: pid_t
        let name: String
        let focusedWindow: AXUIElement?
    }

    private var target: Target?
    private var activationObserver: NSObjectProtocol?
    private var generation = 0
    private var pendingPaste: DispatchWorkItem?

    func start() {
        guard activationObserver == nil else { return }
        captureFrontmost()
        activationObserver = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didActivateApplicationNotification,
            object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in
                    guard let self, self.activationObserver != nil else { return }
                    self.captureFrontmost()
                }
            }
    }

    deinit {
        if let activationObserver { NSWorkspace.shared.notificationCenter.removeObserver(activationObserver) }
        pendingPaste?.cancel()
    }

    func snapshot() -> Target? { target }

    func stop() {
        if let activationObserver {
            NSWorkspace.shared.notificationCenter.removeObserver(activationObserver)
        }
        activationObserver = nil
        generation += 1
        pendingPaste?.cancel()
        pendingPaste = nil
        target = nil
    }

    func captureFrontmost() {
        guard let app = NSWorkspace.shared.frontmostApplication,
              app.processIdentifier != ProcessInfo.processInfo.processIdentifier else { return }
        var focused: CFTypeRef?
        let element = AXUIElementCreateApplication(app.processIdentifier)
        AXUIElementSetMessagingTimeout(element, 0.5)
        let result = AXUIElementCopyAttributeValue(element, kAXFocusedWindowAttribute as CFString,
                                                   &focused)
        let window: AXUIElement?
        if result == .success, let focused, CFGetTypeID(focused) == AXUIElementGetTypeID() {
            window = (focused as! AXUIElement)
        } else {
            window = nil
        }
        target = Target(pid: app.processIdentifier,
                        name: app.localizedName ?? "previous app", focusedWindow: window)
    }

    var targetName: String { target?.name ?? "previous app" }

    func paste(to target: Target?, expectedText: String, clipboardChange: Int,
               completion: @escaping (Bool, String) -> Void) {
        guard let target else {
            completion(false, "Copied, but no previous app was detected. Focus a text field and try again.")
            return
        }
        guard target.focusedWindow != nil else {
            completion(false, "Copied. Original window could not be verified; paste with Command+V.")
            return
        }
        guard NSPasteboard.general.changeCount == clipboardChange,
              let currentText = NSPasteboard.general.string(forType: .string),
              HistoryPolicy.same(currentText, expectedText) else {
            completion(false, "Paste cancelled because the clipboard changed.")
            return
        }
        guard AXIsProcessTrusted() else {
            completion(false, "Copied. Allow nashclip in Accessibility settings to paste automatically.")
            return
        }
        guard let app = NSRunningApplication(processIdentifier: target.pid) else {
            completion(false, "Copied, but the previous app is no longer running.")
            return
        }
        if #available(macOS 14.0, *) {
            NSApp.yieldActivation(to: app)
            app.activate()
        } else {
            app.activate(options: [.activateIgnoringOtherApps])
        }
        if let window = target.focusedWindow {
            AXUIElementSetMessagingTimeout(window, 0.5)
            AXUIElementPerformAction(window, kAXRaiseAction as CFString)
            let application = AXUIElementCreateApplication(target.pid)
            AXUIElementSetMessagingTimeout(application, 0.5)
            AXUIElementSetAttributeValue(application, kAXFocusedWindowAttribute as CFString, window)
            AXUIElementSetAttributeValue(window, kAXMainAttribute as CFString, kCFBooleanTrue)
        }
        pendingPaste?.cancel()
        generation += 1
        let token = generation
        let work = DispatchWorkItem { [weak self] in
            guard let self, token == self.generation else { return }
            self.pendingPaste = nil
            guard NSWorkspace.shared.frontmostApplication?.processIdentifier == target.pid else {
                completion(false, "Copied, but the previous app did not regain focus.")
                return
            }
            if let expectedWindow = target.focusedWindow {
                var current: CFTypeRef?
                let application = AXUIElementCreateApplication(target.pid)
                AXUIElementSetMessagingTimeout(application, 0.5)
                let result = AXUIElementCopyAttributeValue(
                    application, kAXFocusedWindowAttribute as CFString, &current)
                guard result == .success, let current,
                      CFGetTypeID(current) == AXUIElementGetTypeID(),
                      CFEqual(expectedWindow, current) else {
                    completion(false, "Copied, but the original window did not regain focus.")
                    return
                }
            }
            guard NSPasteboard.general.changeCount == clipboardChange,
                  let currentText = NSPasteboard.general.string(forType: .string),
                  HistoryPolicy.same(currentText, expectedText) else {
                completion(false, "Paste cancelled because the clipboard changed.")
                return
            }
            guard let down = CGEvent(keyboardEventSource: nil,
                                     virtualKey: CGKeyCode(kVK_ANSI_V), keyDown: true),
                  let up = CGEvent(keyboardEventSource: nil,
                                   virtualKey: CGKeyCode(kVK_ANSI_V), keyDown: false) else {
                completion(false, "Copied, but macOS could not create a paste event.")
                return
            }
            down.flags = .maskCommand
            up.flags = .maskCommand
            down.post(tap: .cghidEventTap)
            up.post(tap: .cghidEventTap)
            completion(true, "Cmd+V sent to \(target.name).")
        }
        pendingPaste = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3, execute: work)
    }
}
