import AppKit
import SwiftUI

struct Palette {
    let dark: Bool
    var background: Color { dark ? Color(red: 0.118, green: 0.110, blue: 0.102)
                                 : Color(red: 1, green: 0.977, blue: 0.914) }
    var card: Color { dark ? Color(red: 0.169, green: 0.157, blue: 0.137)
                           : Color(red: 1, green: 0.993, blue: 0.958) }
    var border: Color { dark ? Color(red: 0.341, green: 0.294, blue: 0.208)
                             : Color(red: 0.913, green: 0.859, blue: 0.725) }
    var text: Color { dark ? Color(red: 0.973, green: 0.933, blue: 0.835)
                           : Color(red: 0.25, green: 0.19, blue: 0.10) }
    var muted: Color { dark ? Color(red: 0.745, green: 0.682, blue: 0.553)
                            : Color(red: 0.54, green: 0.46, blue: 0.32) }
    var accent: Color { dark ? Color(red: 0.357, green: 0.267, blue: 0.141)
                             : Color(red: 0.96, green: 0.81, blue: 0.42) }
    var accentText: Color { dark ? Color(red: 1, green: 0.855, blue: 0.502)
                                 : Color(red: 0.42, green: 0.26, blue: 0.04) }
    var live: Color { dark ? Color(red: 0.608, green: 0.827, blue: 0.565) : .green }
    var pinActiveText: Color { dark ? Color(red: 0.867, green: 0.694, blue: 0.345)
                                    : Color(red: 0.357, green: 0.208, blue: 0.035) }
    var pinActiveBackground: Color { dark ? Color(red: 0.275, green: 0.204, blue: 0.118)
                                          : Color(red: 0.937, green: 0.875, blue: 0.659) }
}

struct CopierView: View {
    @ObservedObject var model: ClipboardModel
    @ObservedObject var windowLayout: WindowLayoutState
    let useClip: (Clip) -> Void
    let deleteClip: (Clip) -> Void
    let togglePin: () -> Void
    let toggleTheme: () -> Void
    let hideWindow: () -> Void
    let quitApp: () -> Void
    let changeSize: () -> Void
    let dragHeader: (NSPoint, Bool) -> Void
    let dragResize: (ResizeEdge, NSPoint, Bool) -> Void
    private var palette: Palette { Palette(dark: model.settings.theme == "dark") }

    var body: some View {
        VStack(spacing: 0) {
            header
                .contentShape(Rectangle())
                .simultaneousGesture(DragGesture(minimumDistance: 5)
                    .onChanged { _ in dragHeader(NSEvent.mouseLocation, false) }
                    .onEnded { _ in dragHeader(NSEvent.mouseLocation, true) })
            Rectangle().fill(palette.border).frame(height: 1)
            ScrollView(.vertical) {
                if model.clips.isEmpty {
                    VStack(spacing: 10) {
                        Text("A little space for your next idea")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(palette.text)
                        Text("Copy some text. It will appear here, ready to use again.")
                            .font(.system(size: 12))
                            .foregroundStyle(palette.muted)
                    }
                    .frame(maxWidth: .infinity, minHeight: 170)
                } else {
                    let columns = masonryColumns(model.clips)
                    HStack(alignment: .top, spacing: 12) {
                        ForEach(0..<columns.count, id: \.self) { column in
                            VStack(spacing: 12) {
                                ForEach(columns[column]) { clip in
                                    ClipTile(clip: clip, palette: palette,
                                             pinned: model.isPinned(clip),
                                             togglePin: { model.toggleClipPin(clip.id) },
                                             use: { useClip(clip) },
                                             delete: { deleteClip(clip) })
                                }
                            }
                            .frame(width: 220)
                        }
                    }
                    .padding(.vertical, 10)
                    .frame(maxWidth: .infinity)
                }
            }
            .scrollIndicators(.visible)
            Rectangle().fill(palette.border).frame(height: 1)
            HStack {
                Text(model.status).lineLimit(1)
                Spacer()
                Text("Local text history  ·  \(model.settings.historyLimit) clip limit")
            }
            .font(.system(size: 11))
            .foregroundStyle(palette.muted)
            .padding(.top, 8)
        }
        .padding(.horizontal, 14)
        .padding(.top, 10)
        .padding(.bottom, 8)
        .frame(width: windowLayout.width, height: windowLayout.height)
        .background(RoundedRectangle(cornerRadius: 18).fill(palette.background))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(palette.border, lineWidth: 1))
        .overlay(alignment: .top) {
            if let notice = model.rejectedClip {
                SizeNoticeView(notice: notice, seconds: model.settings.sizeNoticeSeconds,
                               drawerOpen: windowLayout.isDrawerOpen,
                               dismiss: model.dismissSizeNotice)
                    .id(notice.id)
                    .padding(.horizontal, 16).padding(.top, 62)
            }
        }
        .overlay(alignment: .leading) { resizeStrip(.left) }
        .overlay(alignment: .trailing) { resizeStrip(.right) }
        .overlay(alignment: .top) { resizeStrip(.top) }
        .overlay(alignment: .bottom) { resizeStrip(.bottom) }
        .environment(\.colorScheme, palette.dark ? .dark : .light)
    }

    private func resizeStrip(_ edge: ResizeEdge) -> some View {
        let horizontal = edge == .left || edge == .right
        return Color.clear
            .frame(width: horizontal ? 8 : nil, height: horizontal ? nil : 8)
            .contentShape(Rectangle())
            .onHover { inside in
                let active = edge == .top || edge == .bottom
                    || (windowLayout.rightSide ? edge == .left : edge == .right)
                if inside && active {
                    (horizontal ? NSCursor.resizeLeftRight : NSCursor.resizeUpDown).set()
                } else {
                    NSCursor.arrow.set()
                }
            }
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { _ in dragResize(edge, NSEvent.mouseLocation, false) }
                .onEnded { _ in dragResize(edge, NSEvent.mouseLocation, true) })
    }

    private var header: some View {
        HStack(spacing: 10) {
            Image(systemName: "doc.on.clipboard.fill")
                .font(.system(size: 21, weight: .medium))
                .foregroundStyle(palette.accentText)
                .frame(width: 38, height: 38)
                .background(palette.accent, in: RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 2) {
                Text("nashclip").font(.system(size: 18, weight: .bold))
                    .foregroundStyle(palette.text)
                Text("\(model.clips.count) \(model.clips.count == 1 ? "clip" : "clips") · \(model.pinnedTexts.count)/5 pinned")
                    .font(.system(size: 11)).foregroundStyle(palette.muted)
            }
            Spacer()
            Button(action: changeSize) {
                Image(systemName: "arrow.up.left.and.arrow.down.right")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(palette.accentText)
                    .frame(width: 29, height: 29)
                    .background(palette.accent.opacity(0.55),
                                in: RoundedRectangle(cornerRadius: 9))
            }
            .buttonStyle(.plain)
            .help("Change window size")
            Button(action: toggleTheme) {
                Image(systemName: palette.dark ? "sun.max" : "moon")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(palette.accentText)
                    .frame(width: 29, height: 29)
                    .background(palette.accent.opacity(0.55),
                                in: RoundedRectangle(cornerRadius: 9))
            }
            .buttonStyle(.plain)
            .help(palette.dark ? "Switch to light mode" : "Switch to dark mode")
            Button(action: togglePin) {
                Image(systemName: model.settings.pinned ? "pin.fill" : "pin")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(model.settings.pinned ? palette.pinActiveText : palette.accentText)
                    .frame(width: 29, height: 29)
                    .background(model.settings.pinned ? palette.pinActiveBackground : .clear,
                                in: RoundedRectangle(cornerRadius: 9))
            }
            .buttonStyle(.plain)
            .help(model.settings.pinned ? "Unpin nashclip" : "Pin nashclip open")
            Button(action: hideWindow) { Image(systemName: "minus").frame(width: 22, height: 29) }
                .buttonStyle(.plain).foregroundStyle(palette.accentText)
                .help("Hide to edge (keeps capturing)").accessibilityLabel("Hide nashclip")
            Button(action: quitApp) { Image(systemName: "xmark").frame(width: 22, height: 29) }
                .buttonStyle(.plain).foregroundStyle(palette.accentText)
                .help("Quit nashclip (stops capturing)").accessibilityLabel("Quit nashclip")
            VStack(alignment: .trailing, spacing: 2) {
                Button(model.paused ? "Ⅱ Paused" : "● Live") { model.togglePaused() }
                    .buttonStyle(.plain).foregroundStyle(palette.live)
                    .help("Pause or resume clipboard capture for this session")
                Text("⌃⌥V  Show clips").foregroundStyle(palette.muted)
            }
            .font(.system(size: 11))
        }
        .padding(.bottom, 9)
    }

    func masonryColumns(_ clips: [Clip]) -> [[Clip]] {
        let available = windowLayout.width - 28 - 9
        let count = max(1, min(3, Int((available + 12) / 232)))
        var result = Array(repeating: [Clip](), count: count)
        var heights = Array(repeating: CGFloat.zero, count: count)
        for clip in clips {
            let column = heights.enumerated().min(by: { $0.element < $1.element })!.offset
            result[column].append(clip)
            let measured = (HistoryPolicy.preview(clip.text) as NSString).boundingRect(
                with: NSSize(width: 194, height: CGFloat.greatestFiniteMagnitude),
                options: [.usesLineFragmentOrigin, .usesFontLeading],
                attributes: [.font: NSFont.systemFont(ofSize: 13)]).height
            heights[column] += max(48, measured) + 94 + 12
        }
        return result
    }
}

@MainActor
final class WindowLayoutState: ObservableObject {
    @Published var width: CGFloat
    @Published var height: CGFloat
    @Published var rightSide = true
    @Published var isDrawerOpen = false

    init(width: CGFloat, height: CGFloat) {
        self.width = width
        self.height = height
    }
}

private struct ClipTile: View {
    let clip: Clip
    let palette: Palette
    let pinned: Bool
    let togglePin: () -> Void
    let use: () -> Void
    let delete: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(pinned ? "PINNED" : "≡  TEXT").font(.system(size: 10, weight: .bold))
                    .foregroundStyle(palette.accentText)
                Spacer()
                Button(action: togglePin) {
                    Image(systemName: pinned ? "pin.fill" : "pin")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(pinned ? palette.pinActiveText : palette.muted)
                        .padding(5)
                        .background(pinned ? palette.pinActiveBackground : .clear,
                                    in: RoundedRectangle(cornerRadius: 6))
                }
                .buttonStyle(.plain)
                .help(pinned ? "Unpin this clip" : "Pin this clip (up to 5)")
                .accessibilityLabel(pinned ? "Unpin this clip" : "Pin this clip")
                Button(action: delete) {
                    Image(systemName: "xmark")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(palette.muted)
                }
                .buttonStyle(.plain)
                .help("Delete this clip")
            }
            Text(HistoryPolicy.preview(clip.text))
                .font(.system(size: 13))
                .foregroundStyle(palette.text)
                .frame(width: 194, alignment: .leading)
                .textSelection(.enabled)
            HStack {
                Text("\(clip.text.count) \(clip.text.count == 1 ? "character" : "characters")")
                    .font(.system(size: 11)).foregroundStyle(palette.muted)
                Spacer(minLength: 4)
                Button(action: use) {
                    Text("Use  ↗").font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(palette.accentText)
                        .padding(.horizontal, 10).padding(.vertical, 6)
                        .background(palette.accent, in: RoundedRectangle(cornerRadius: 8))
                }
                .buttonStyle(.plain)
                .help("Paste into the previous app")
            }
        }
        .padding(12)
        .frame(width: 220, alignment: .leading)
        .background(palette.card, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(palette.border, lineWidth: 1))
    }
}

@MainActor
final class HandleState: ObservableObject {
    @Published var isOpen = false
    @Published var rightSide = true
    @Published var dark = false
}

struct HandleView: View {
    @ObservedObject var state: HandleState
    let toggle: () -> Void
    let drag: (NSPoint, Bool) -> Void
    let hover: (Bool) -> Void
    private var palette: Palette { Palette(dark: state.dark) }

    var body: some View {
        Button(action: toggle) {
            Image(systemName: state.rightSide
                  ? (state.isOpen ? "chevron.right" : "chevron.left")
                  : (state.isOpen ? "chevron.left" : "chevron.right"))
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(palette.accentText)
                .frame(width: 18, height: 96)
                .background(palette.accent, in: RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .onHover(perform: hover)
        .simultaneousGesture(DragGesture(minimumDistance: 5)
            .onChanged { _ in drag(NSEvent.mouseLocation, false) }
            .onEnded { _ in drag(NSEvent.mouseLocation, true) })
        .environment(\.colorScheme, palette.dark ? .dark : .light)
    }
}

/// Keeps only notice metadata; the task is cancelled when hidden, replaced, or dismissed.
private struct SizeNoticeView: View {
    let notice: RejectedClip
    let seconds: Int
    let drawerOpen: Bool
    let dismiss: () -> Void
    @State private var opacity = 0.0

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "exclamationmark.circle.fill")
                .font(.system(size: 23)).foregroundStyle(Color(red: 1, green: 0.71, blue: 0.71))
            VStack(alignment: .leading, spacing: 4) {
                Text("Last copied clip was too large").font(.system(size: 12, weight: .semibold))
                Text(String(format: "%.1f KiB text · 128 KiB limit. Not saved.", Double(notice.units) / 512))
                    .font(.system(size: 11))
            }
            Spacer(minLength: 0)
            Button(action: dismiss) {
                Image(systemName: "xmark").font(.system(size: 13, weight: .semibold))
                    .padding(6)
            }
            .buttonStyle(.plain).help("Dismiss size notice").accessibilityLabel("Dismiss size notice")
        }
        .foregroundStyle(.white)
        .padding(14)
        .frame(maxWidth: 440)
        .background(Color(red: 0.44, green: 0.13, blue: 0.15), in: RoundedRectangle(cornerRadius: 12))
        .opacity(opacity)
        .allowsHitTesting(drawerOpen)
        .task(id: "\(drawerOpen)-\(seconds)") {
            opacity = 0
            guard drawerOpen else { return }
            withAnimation(.easeOut(duration: 0.15)) { opacity = 1 }
            guard seconds > 0 else { return }
            do {
                try await Task.sleep(nanoseconds: UInt64(seconds * 1000 - 300) * 1_000_000)
                withAnimation(.easeIn(duration: 0.3)) { opacity = 0 }
                try await Task.sleep(nanoseconds: 300_000_000)
                guard !Task.isCancelled else { return }
                dismiss()
            } catch { /* Hiding or replacing the notice cancels its timer. */ }
        }
    }
}
