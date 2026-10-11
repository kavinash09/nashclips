import AppKit
import SwiftUI

/// Wires native services to the SwiftUI view. UI work stays on the main thread.
@MainActor
final class AppController: NSObject, NSApplicationDelegate {
    private let storage = CopierStorage()
    private var instanceLock: InstanceLock?
    private let paste = PasteCoordinator()
    private let hotkey = GlobalHotkey()
    private var model: ClipboardModel!
    private var drawer: DrawerController!
    private var windowSize = WindowSize()
    private var windowLayout: WindowLayoutState!
    private var statusItem: NSStatusItem?
    private var pinMenuItem: NSMenuItem?
    private var darkMenuItem: NSMenuItem?
    private var globalClickMonitor: Any?
    private var localClickMonitor: Any?
    private var useInProgress = false

    func applicationDidFinishLaunching(_ notification: Notification) {
        do {
            instanceLock = try InstanceLock(directory: storage.directory)
        } catch {
            let alert = NSAlert()
            alert.messageText = "Cannot start nashclip"
            alert.informativeText = "Another version may be running, or the data directory is unavailable. Quit other versions and try again."
            alert.runModal()
            NSApp.terminate(nil)
            return
        }
        model = ClipboardModel(storage: storage)
        do {
            windowSize = try storage.loadSize()
        } catch {
            fputs("Could not load window size: \(error)\n", stderr)
        }
        windowLayout = WindowLayoutState(width: CGFloat(windowSize.width),
                                         height: CGFloat(windowSize.height))
        let savedPosition: DrawerPosition?
        do {
            savedPosition = try storage.loadPosition()
        } catch {
            savedPosition = nil
            fputs("Could not load drawer position: \(error)\n", stderr)
        }
        let content = CopierView(model: model, windowLayout: windowLayout,
            useClip: { [weak self] clip in self?.use(clip) },
            deleteClip: { [weak self] clip in self?.model.delete(clip.id) },
            togglePin: { [weak self] in self?.togglePin() },
            toggleTheme: { [weak self] in self?.toggleTheme() },
            hideWindow: { [weak self] in self?.drawer.hide() },
            quitApp: { NSApp.terminate(nil) },
            changeSize: { [weak self] in self?.showWindowSize() },
            dragHeader: { [weak self] point, ended in
                self?.drawer.drag(to: point, ended: ended, fromHeader: true)
            },
            dragResize: { [weak self] edge, point, ended in
                self?.drawer.dragResize(edge: edge, to: point, ended: ended)
            })
        drawer = DrawerController(view: content, windowLayout: windowLayout, size: windowSize,
                                  pinned: model.settings.pinned,
                                  dark: model.settings.theme == "dark",
                                  savedPosition: savedPosition,
                                  onMoved: { [weak self] position in
            self?.savePosition(position)
        }, onResized: { [weak self] width, height in
            self?.saveResizedWindow(width: width, height: height)
        })
        installStatusItem()
        installClickMonitors()
        paste.start()
        model.start()
        hotkey.onPress = { [weak self] in
            self?.paste.captureFrontmost()
            self?.drawer.show()
        }
        let status = hotkey.start()
        if status != noErr {
            model.status = "Shortcut unavailable. Open nashclip from the menu bar."
            fputs("Global shortcut unavailable: \(status)\n", stderr)
        }
    }

    func applicationWillTerminate(_ notification: Notification) {
        drawer?.close()
        model?.stop()
        paste.stop()
        hotkey.stop()
        if let globalClickMonitor { NSEvent.removeMonitor(globalClickMonitor) }
        if let localClickMonitor { NSEvent.removeMonitor(localClickMonitor) }
        if let statusItem { NSStatusBar.system.removeStatusItem(statusItem) }
        globalClickMonitor = nil
        localClickMonitor = nil
        self.statusItem = nil
        instanceLock = nil
    }

    private func use(_ clip: Clip) {
        guard !useInProgress else { return }
        useInProgress = true
        paste.captureFrontmost()
        guard model.copy(clip.text) else { useInProgress = false; return }
        let destination = paste.snapshot()
        let clipboardChange = NSPasteboard.general.changeCount
        model.status = "Copied; restoring \(paste.targetName)..."
        let accepted = drawer.hide { [weak self] in
            guard let self else { return }
            self.paste.paste(to: destination, expectedText: clip.text, clipboardChange: clipboardChange) { [weak self] sent, message in
                guard let self else { return }
                self.useInProgress = false
                self.model.status = message
                if !sent || self.drawer.isPinned { self.drawer.show() }
            }
        }
        if !accepted {
            useInProgress = false
            model.status = "Copied. Finish moving or resizing the drawer, then try Use again."
        }
    }

    private func togglePin() {
        let value = !model.settings.pinned
        do {
            try model.updatePinned(value)
            drawer.setPinned(value)
            pinMenuItem?.state = value ? .on : .off
        } catch {
            showError("Could not save pin setting", error)
        }
    }

    private func toggleTheme() {
        let next = model.settings.theme == "dark" ? "light" : "dark"
        do {
            try model.updateTheme(next)
            drawer.setTheme(next)
            darkMenuItem?.state = next == "dark" ? .on : .off
        } catch {
            showError("Could not save theme", error)
        }
    }

    private func savePosition(_ position: DrawerPosition) {
        do {
            try storage.savePosition(position)
            model.status = "Drawer position saved."
        } catch {
            model.status = "Could not save drawer position."
            fputs("Could not save drawer position: \(error)\n", stderr)
        }
    }

    private func installClickMonitors() {
        let events: NSEvent.EventTypeMask = [.leftMouseDown, .rightMouseDown, .otherMouseDown]
        globalClickMonitor = NSEvent.addGlobalMonitorForEvents(matching: events) { [weak self] _ in
            Task { @MainActor in self?.drawer.click(at: NSEvent.mouseLocation) }
        }
        if globalClickMonitor == nil {
            model.status = "Outside-click detection unavailable. Use the arrow to close."
        }
        localClickMonitor = NSEvent.addLocalMonitorForEvents(matching: events) { [weak self] event in
            self?.drawer.click(at: NSEvent.mouseLocation)
            return event
        }
    }

    private func installStatusItem() {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        let image = NSImage(systemSymbolName: "doc.on.clipboard",
                            accessibilityDescription: "nashclip")
        image?.isTemplate = true
        item.button?.image = image
        item.button?.toolTip = "nashclip clipboard history"
        let menu = NSMenu()
        menu.addItem(menuItem("Show nashclip (Control+Option+V)", #selector(showDrawer)))
        let pin = menuItem("Pin Drawer Open", #selector(togglePinFromMenu))
        pin.state = model.settings.pinned ? .on : .off
        pinMenuItem = pin
        menu.addItem(pin)
        let dark = menuItem("Dark Mode", #selector(toggleThemeFromMenu))
        dark.state = model.settings.theme == "dark" ? .on : .off
        darkMenuItem = dark
        menu.addItem(dark)
        menu.addItem(menuItem("Window Size…", #selector(showWindowSize)))
        menu.addItem(menuItem("Settings…", #selector(showSettings)))
        menu.addItem(menuItem("Clear History…", #selector(clearHistory)))
        menu.addItem(.separator())
        menu.addItem(menuItem("Quit nashclip", #selector(quit)))
        item.menu = menu
        statusItem = item
    }

    private func menuItem(_ title: String, _ action: Selector) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: "")
        item.target = self
        return item
    }

    @objc private func showDrawer() {
        paste.captureFrontmost()
        drawer.show()
    }

    @objc private func togglePinFromMenu() { togglePin() }

    @objc private func toggleThemeFromMenu() { toggleTheme() }

    @objc private func showWindowSize() {
        drawer.setModalInteraction(true)
        drawer.show()
        NSApp.activate(ignoringOtherApps: true)
        let width = NSTextField(string: String(windowSize.width))
        let height = NSTextField(string: String(windowSize.height))
        let options = NSStackView(views: [
            NSTextField(labelWithString: "Width (pixels):"), width,
            NSTextField(labelWithString: "Height (pixels):"), height,
            NSTextField(labelWithString: "Saved default: \(windowSize.defaultWidth) × \(windowSize.defaultHeight)")
        ])
        options.orientation = .vertical
        options.spacing = 7
        options.frame = NSRect(x: 0, y: 0, width: 250, height: 145)
        let alert = NSAlert()
        setAppearance(for: alert)
        alert.messageText = "Window Size"
        alert.informativeText = "Choose a size for the sliding panel."
        alert.accessoryView = options
        alert.addButton(withTitle: "Apply size")
        alert.addButton(withTitle: "Save as default")
        alert.addButton(withTitle: "Reset to default")
        alert.addButton(withTitle: "Cancel")
        let choice = alert.runModal()
        drawer.setModalInteraction(false)
        guard choice.rawValue != NSApplication.ModalResponse.alertThirdButtonReturn.rawValue + 1 else { return }
        let next: WindowSize
        if choice == .alertThirdButtonReturn {
            next = windowSize.resetToDefault()
        } else {
            guard let valueWidth = Int(width.stringValue),
                  let valueHeight = Int(height.stringValue) else {
                showError("Enter whole numbers for width and height", nil)
                return
            }
            let proposed = windowSize.withCurrent(width: valueWidth, height: valueHeight)
            next = choice == .alertSecondButtonReturn ? proposed.savingCurrentAsDefault() : proposed
        }
        guard next.valid else {
            showError("Width must be 500–4096 and height 260–2160", nil)
            return
        }
        do {
            try storage.saveSize(next)
            windowSize = next
            drawer.resize(width: next.width, height: next.height)
            model.status = choice == .alertSecondButtonReturn ? "Default window size saved."
                : choice == .alertThirdButtonReturn ? "Window size reset to default."
                : "Window size saved."
        } catch {
            showError("Could not save window size", error)
        }
    }

    private func saveResizedWindow(width: Int, height: Int) {
        do {
            let next = windowSize.withCurrent(width: width, height: height)
            try storage.saveSize(next)
            windowSize = next
            model.status = "Window size saved."
        } catch {
            model.status = "Could not save window size."
            fputs("Could not save window size: \(error)\n", stderr)
        }
    }

    @objc private func showSettings() {
        drawer.setModalInteraction(true)
        drawer.show()
        NSApp.activate(ignoringOtherApps: true)
        let field = NSTextField(string: String(model.settings.historyLimit))
        field.placeholderString = "Maximum clips"
        let checkbox = NSButton(checkboxWithTitle: "Keep drawer open", target: nil, action: nil)
        checkbox.state = model.settings.pinned ? .on : .off
        let darkCheckbox = NSButton(checkboxWithTitle: "Dark mode", target: nil, action: nil)
        darkCheckbox.state = model.settings.theme == "dark" ? .on : .off
        let options = NSStackView(views: [field, checkbox, darkCheckbox])
        options.orientation = .vertical
        options.spacing = 9
        options.frame = NSRect(x: 0, y: 0, width: 240, height: 82)
        let alert = NSAlert()
        setAppearance(for: alert)
        alert.messageText = "nashclip Settings"
        alert.informativeText = "A lower limit removes the oldest clips."
        alert.accessoryView = options
        alert.addButton(withTitle: "Save")
        alert.addButton(withTitle: "Cancel")
        let choice = alert.runModal()
        drawer.setModalInteraction(false)
        guard choice == .alertFirstButtonReturn else { return }
        guard let limit = Int(field.stringValue), (1...HistoryPolicy.maxEntries).contains(limit) else {
            showError("Enter a history limit from 1 to 500", nil)
            return
        }
        let pinned = checkbox.state == .on
        do {
            let theme = darkCheckbox.state == .on ? "dark" : "light"
            try model.updateSettings(limit: limit, pinned: pinned, theme: theme)
            drawer.setPinned(pinned)
            drawer.setTheme(theme)
            pinMenuItem?.state = pinned ? .on : .off
            darkMenuItem?.state = theme == "dark" ? .on : .off
        } catch {
            showError("Could not save settings", error)
        }
    }

    @objc private func clearHistory() {
        let alert = NSAlert()
        setAppearance(for: alert)
        alert.messageText = "Clear unpinned clipboard history?"
        alert.informativeText = "Pinned clips will be kept. All other saved clips will be deleted."
        alert.addButton(withTitle: "Clear History")
        alert.addButton(withTitle: "Cancel")
        if alert.runModal() == .alertFirstButtonReturn { model.clearHistory() }
    }

    @objc private func quit() { NSApp.terminate(nil) }

    private func showError(_ message: String, _ error: Error?) {
        let alert = NSAlert()
        setAppearance(for: alert)
        alert.alertStyle = .warning
        alert.messageText = message
        if let error { alert.informativeText = error.localizedDescription }
        alert.runModal()
    }

    private func setAppearance(for alert: NSAlert) {
        alert.window.appearance = NSAppearance(named: model.settings.theme == "dark" ? .darkAqua : .aqua)
    }
}
