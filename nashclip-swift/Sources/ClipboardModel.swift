import AppKit
import Combine

struct Clip: Identifiable {
    let id = UUID()
    let text: String
}

struct RejectedClip: Identifiable {
    let id = UUID()
    let units: Int
}

/// The model owns clipboard polling, the history limit, and disk writes.
@MainActor
final class ClipboardModel: ObservableObject {
    @Published private(set) var clips: [Clip] = []
    @Published private(set) var pinnedTexts: [String] = []
    @Published private(set) var settings = CopierSettings()
    @Published private(set) var paused = false
    @Published private(set) var rejectedClip: RejectedClip?
    @Published var status = "Use pastes and hides this window."

    private let storage: CopierStorage
    private let pasteboard: NSPasteboard
    private var timer: Timer?
    private var lastChangeCount: Int
    private var lastObservedText: String?
    private var settingsLoadFailed = false
    private var historyLoadFailed = false

    init(storage: CopierStorage, pasteboard: NSPasteboard = .general) {
        self.storage = storage
        self.pasteboard = pasteboard
        lastChangeCount = pasteboard.changeCount
        let initial = pasteboard.string(forType: .string)
        lastObservedText = initial.flatMap { HistoryPolicy.accepts($0) ? $0 : nil }
        do {
            settings = try storage.loadSettings()
        } catch {
            settingsLoadFailed = true
            status = "Settings unavailable; history saving paused."
            fputs("Could not load settings: \(error)\n", stderr)
        }
        do {
            let saved = try storage.loadHistorySnapshot(limit: settings.historyLimit)
            pinnedTexts = saved.pinned
            clips = saved.entries.map(Clip.init(text:))
        } catch {
            historyLoadFailed = true
            status = "Could not read history; saving paused."
            fputs("Could not load history: \(error)\n", stderr)
        }
    }

    func dismissSizeNotice() { rejectedClip = nil }

    func togglePaused() {
        paused.toggle()
        lastChangeCount = pasteboard.changeCount
        lastObservedText = nil
        status = paused ? "Capture paused until resumed or restarted."
                        : "Capture resumed. Text copied while paused is skipped."
    }

    func start() {
        guard timer == nil else { return }
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.checkClipboard() }
        }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        lastObservedText = nil
        rejectedClip = nil
    }

    deinit { timer?.invalidate() }

    func copy(_ text: String) -> Bool {
        pasteboard.clearContents()
        guard pasteboard.setString(text, forType: .string) else {
            status = "Clipboard unavailable. Try Use again."
            return false
        }
        lastChangeCount = pasteboard.changeCount
        lastObservedText = text
        return true
    }

    func isPinned(_ clip: Clip) -> Bool { HistoryPolicy.contains(pinnedTexts, clip.text) }

    func toggleClipPin(_ id: UUID) {
        guard let clip = clips.first(where: { $0.id == id }) else { return }
        var next = pinnedTexts
        let removing = HistoryPolicy.contains(next, clip.text)
        if removing { next.removeAll { HistoryPolicy.same($0, clip.text) } }
        else {
            guard next.count < HistoryPolicy.maxPinned else {
                status = "Only 5 clips can be pinned. Unpin one first."
                return
            }
            next.insert(clip.text, at: 0)
        }
        if commit(clips, pinned: next) {
            status = removing ? "Clip unpinned." : "Clip pinned · \(next.count)/5"
        }
    }

    func delete(_ id: UUID) {
        guard let clip = clips.first(where: { $0.id == id }) else { return }
        _ = commit(clips.filter { $0.id != id }, pinned: pinnedTexts.filter { !HistoryPolicy.same($0, clip.text) })
    }

    func clearHistory() {
        guard !historyLoadFailed, !settingsLoadFailed else {
            status = "Cannot clear history until the data-file error is fixed."
            return
        }
        do {
            try storage.saveHistory([], limit: settings.historyLimit, pinned: pinnedTexts)
            clips = clips.filter { HistoryPolicy.contains(pinnedTexts, $0.text) }
            historyLoadFailed = false
            status = "Unpinned history cleared. Pinned clips and the system clipboard are unchanged."
        } catch {
            status = "Could not clear saved history."
        }
    }

    private func ordered(_ entries: [Clip], pinned: [String]) throws -> [Clip] {
        let texts = try HistoryPolicy.withPins(entries.map(\.text), pinned: pinned, limit: settings.historyLimit)
        var available = entries
        return texts.map { text in
            // Preserve identities, including distinct ordinary copies of the same text.
            if let index = available.firstIndex(where: { HistoryPolicy.same($0.text, text) }) { return available.remove(at: index) }
            return Clip(text: text)
        }
    }

    private func commit(_ entries: [Clip], pinned: [String]) -> Bool {
        guard !settingsLoadFailed, !historyLoadFailed else {
            status = "Cannot save clips until the data-file error is fixed."
            return false
        }
        do {
            let next = try ordered(entries, pinned: pinned)
            try storage.saveHistory(next.map(\.text), limit: settings.historyLimit, pinned: pinned)
            pinnedTexts = pinned
            clips = next
            return true
        } catch {
            status = "Could not save clip changes. Nothing was changed."
            return false
        }
    }

    func updateSettings(limit: Int, pinned: Bool, theme: String) throws {
        guard (1...HistoryPolicy.maxEntries).contains(limit) else { throw StorageError.invalidFile("History limit must be 1–500") }
        let current = try storage.loadSettings()
        let next = CopierSettings(historyLimit: limit, pinned: pinned, theme: theme, sizeNoticeSeconds: current.sizeNoticeSeconds)
        try storage.saveSettings(next)
        settingsLoadFailed = false
        settings = next
        trimHistory()
        if saveHistory() { status = "Settings saved." }
    }

    func updatePinned(_ value: Bool) throws {
        let current = try storage.loadSettings()
        try storage.saveSettings(CopierSettings(historyLimit: current.historyLimit, pinned: value, theme: current.theme, sizeNoticeSeconds: current.sizeNoticeSeconds))
        settings.pinned = value
        status = value ? "Pinned open. Use the arrow to close."
                       : "Unpinned. Outside click closes until you click inside."
    }

    func updateTheme(_ value: String) throws {
        let current = try storage.loadSettings()
        try storage.saveSettings(CopierSettings(historyLimit: current.historyLimit, pinned: current.pinned, theme: value, sizeNoticeSeconds: current.sizeNoticeSeconds))
        settings.theme = value
        status = value == "dark" ? "Dark theme on." : "Light theme on."
    }

    func checkClipboard() {
        guard timer != nil, !paused else { return }
        let count = pasteboard.changeCount
        guard count != lastChangeCount else { return }
        let observed = pasteboard.string(forType: .string)
        // A producer may advertise text before making the promised data available.
        if observed == nil && pasteboard.availableType(from: [.string]) != nil { return }
        guard pasteboard.changeCount == count else { return }
        lastChangeCount = count
        guard let text = observed, HistoryPolicy.accepts(text) else {
            lastObservedText = nil
            if let observed, observed.utf16.count > HistoryPolicy.maxTextUnits {
                rejectedClip = RejectedClip(units: observed.utf16.count)
            }
            return
        }
        guard lastObservedText.map({ !HistoryPolicy.same(text, $0) }) ?? true else { return }
        lastObservedText = text
        guard !HistoryPolicy.contains(pinnedTexts, text) else { return }
        clips.insert(Clip(text: text), at: 0)
        trimHistory()
        saveHistory()
    }

    private func trimHistory() {
        do { clips = try ordered(clips, pinned: pinnedTexts) }
        catch { status = "Could not apply history limits." }
    }

    @discardableResult
    private func saveHistory() -> Bool {
        guard !settingsLoadFailed, !historyLoadFailed else {
            status = "History saving paused because a data file could not be loaded."
            return false
        }
        do {
            try storage.saveHistory(clips.map(\.text), limit: settings.historyLimit, pinned: pinnedTexts)
            return true
        } catch {
            status = "Could not save history."
            fputs("Could not save history: \(error)\n", stderr)
            return false
        }
    }
}
