import AppKit
import QuartzCore
import SwiftUI

enum ResizeEdge: Equatable { case left, right, top, bottom }

/// Positions two native panels: the always-visible handle and the sliding history window.
@MainActor
final class DrawerController {
    let panel: NSPanel
    let handle: NSPanel
    let handleState = HandleState()
    private let windowLayout: WindowLayoutState

    private let onMoved: (DrawerPosition) -> Void
    private let onResized: (Int, Int) -> Void
    private var screen: NSRect = .zero
    private var panelWidth: CGFloat = 760
    private var panelHeight: CGFloat = 340
    private var desiredWidth: CGFloat
    private var desiredHeight: CGFloat
    private var panelY: CGFloat = 0
    private var handleY: CGFloat = 0
    private var rightSide = true
    private var open = false
    private var closed = false
    private var pinned: Bool
    private var clickedInside = false
    private var modalInteraction = false
    private var suppressHover = false
    private var hoverWork: DispatchWorkItem?
    private var animationToken = 0
    private var dragging = false
    private var resizing = false
    private var resizeEdge: ResizeEdge?
    private var resizeStart: NSPoint = .zero
    private var resizeWidthAtStart: CGFloat = 0
    private var resizeHeightAtStart: CGFloat = 0
    private var resizeBottomAtStart: CGFloat = 0
    private var lastDragEnded = Date.distantPast
    private var dragStart: NSPoint = .zero
    private var panelAtDragStart: NSRect = .zero
    private var handleAtDragStart: NSRect = .zero
    private var dragWasOpen = false
    private var pendingClose: (() -> Void)?

    init(view: CopierView, windowLayout: WindowLayoutState, size: WindowSize,
         pinned: Bool, dark: Bool, savedPosition: DrawerPosition?,
         onMoved: @escaping (DrawerPosition) -> Void,
         onResized: @escaping (Int, Int) -> Void) {
        self.pinned = pinned
        self.onMoved = onMoved
        self.onResized = onResized
        self.windowLayout = windowLayout
        desiredWidth = CGFloat(size.width)
        desiredHeight = CGFloat(size.height)
        panel = Self.makePanel(width: CGFloat(size.width), height: CGFloat(size.height))
        handle = Self.makePanel(width: 18, height: 96)
        panel.hasShadow = true
        handle.hasShadow = false

        let hosting = NSHostingView(rootView: view)
        hosting.frame = NSRect(x: 0, y: 0, width: desiredWidth, height: desiredHeight)
        hosting.autoresizingMask = [.width, .height]
        panel.contentView = hosting

        if let savedPosition {
            screen = Self.savedScreen(for: savedPosition)
            rightSide = savedPosition.rightSide
        } else {
            screen = NSScreen.main?.visibleFrame ?? NSScreen.screens.first?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
        }
        updateSize()
        if let savedPosition {
            handleY = screen.minY + CGFloat(savedPosition.verticalFraction)
                * max(0, screen.height - 96)
            panelY = min(max(handleY - (panelHeight - 96) / 2, screen.minY),
                         screen.maxY - panelHeight)
        } else {
            panelY = screen.minY + 12
            handleY = panelY + (panelHeight - 96) / 2
        }

        handleState.rightSide = rightSide
        windowLayout.rightSide = rightSide
        handleState.dark = dark
        handle.contentView = NSHostingView(rootView: HandleView(
            state: handleState,
            toggle: { [weak self] in self?.toggle() },
            drag: { [weak self] point, ended in
                self?.drag(to: point, ended: ended, fromHeader: false)
            },
            hover: { [weak self] inside in self?.hover(inside) }))
        positionWindows(open: false)
        handle.orderFrontRegardless()
        if pinned { show() }
    }

    func close() {
        guard !closed else { return }
        closed = true
        open = false
        windowLayout.isDrawerOpen = false
        animationToken += 1
        hoverWork?.cancel()
        hoverWork = nil
        pendingClose = nil
        panel.orderOut(nil)
        handle.orderOut(nil)
        panel.contentView = nil
        handle.contentView = nil
        panel.close()
        handle.close()
    }

    var isPinned: Bool { pinned }

    func setTheme(_ theme: String) {
        handleState.dark = theme == "dark"
    }

    func setPinned(_ value: Bool) {
        pinned = value
        if value { show() }
    }

    func setModalInteraction(_ value: Bool) {
        modalInteraction = value
    }

    func resize(width: Int, height: Int) {
        guard !closed else { return }
        if pendingClose != nil { finishClose() }
        dragging = false
        resizing = false
        animationToken += 1
        desiredWidth = CGFloat(width)
        desiredHeight = CGFloat(height)
        updateSize()
        adjustVerticalPosition()
        positionWindows(open: open)
    }

    func dragResize(edge: ResizeEdge, to point: NSPoint, ended: Bool) {
        if !resizing {
            guard !ended, open, !dragging, canResize(from: edge) else { return }
            animationToken += 1
            resizeEdge = edge
            resizeStart = point
            resizeWidthAtStart = panelWidth
            resizeHeightAtStart = panelHeight
            resizeBottomAtStart = panelY
            resizing = true
            clickedInside = true
            positionWindows(open: true)
            return
        }
        guard resizeEdge == edge else { return }
        let maxWidth = max(1, min(CGFloat(WindowSize.maxWidth), screen.width - 18))
        let minWidth = min(CGFloat(WindowSize.minWidth), maxWidth)
        let maxHeight = max(1, min(CGFloat(WindowSize.maxHeight), screen.height - 24))
        let minHeight = min(CGFloat(WindowSize.minHeight), maxHeight)
        switch edge {
        case .left, .right:
            let delta = point.x - resizeStart.x
            panelWidth = min(max(resizeWidthAtStart + (rightSide ? -delta : delta), minWidth), maxWidth)
            desiredWidth = panelWidth
        case .top:
            let limit = min(maxHeight, screen.maxY - resizeBottomAtStart)
            panelHeight = min(max(resizeHeightAtStart + point.y - resizeStart.y,
                                  min(minHeight, limit)), limit)
            panelY = resizeBottomAtStart
            desiredHeight = panelHeight
        case .bottom:
            let top = resizeBottomAtStart + resizeHeightAtStart
            let limit = min(maxHeight, top - screen.minY)
            panelHeight = min(max(resizeHeightAtStart + resizeStart.y - point.y,
                                  min(minHeight, limit)), limit)
            panelY = top - panelHeight
            desiredHeight = panelHeight
        }
        handleY = panelY + (panelHeight - 96) / 2
        windowLayout.width = panelWidth
        windowLayout.height = panelHeight
        positionWindows(open: true)
        if ended {
            resizing = false
            resizeEdge = nil
            guard panelWidth != resizeWidthAtStart || panelHeight != resizeHeightAtStart else { return }
            let fraction = (handleY - screen.minY) / max(1, screen.height - 96)
            onMoved(DrawerPosition(screenX: screen.minX, screenY: screen.minY,
                                   screenWidth: screen.width, screenHeight: screen.height,
                                   rightSide: rightSide, verticalFraction: fraction))
            onResized(Int(panelWidth.rounded()), Int(panelHeight.rounded()))
        }
    }

    private func canResize(from edge: ResizeEdge) -> Bool {
        switch edge {
        case .top, .bottom: return true
        case .left: return rightSide
        case .right: return !rightSide
        }
    }

    func show() {
        guard !closed else { return }
        guard !dragging, !resizing, pendingClose == nil else { return }
        hoverWork?.cancel()
        screen = Self.screen(containing: NSPoint(x: handle.frame.midX, y: handle.frame.midY))
        updateSize()
        adjustVerticalPosition()
        guard !open else { return }
        open = true
        clickedInside = false
        suppressHover = false
        handleState.isOpen = true
        windowLayout.isDrawerOpen = true
        panel.orderFrontRegardless()
        handle.orderFrontRegardless()
        animate(open: true, duration: 0.34)
    }

    @discardableResult
    func hide(completion: (() -> Void)? = nil) -> Bool {
        guard !closed, !resizing, !dragging else { return false }
        if completion != nil && pendingClose != nil { return false }
        if !open {
            if let completion {
                if panel.isVisible { pendingClose = completion }
                else { completion() }
            }
            return true
        }
        hoverWork?.cancel()
        open = false
        clickedInside = false
        suppressHover = true
        pendingClose = completion
        handleState.isOpen = false
        windowLayout.isDrawerOpen = false
        animate(open: false, duration: 0.34)
        return true
    }

    func toggle() {
        if dragging || Date().timeIntervalSince(lastDragEnded) < 0.25 { return }
        if open { hide() } else { show() }
    }

    func hover(_ inside: Bool) {
        guard !closed else { return }
        handle.alphaValue = inside ? 1 : 0.66
        hoverWork?.cancel()
        if !inside {
            suppressHover = false
            return
        }
        guard !open, !suppressHover else { return }
        let work = DispatchWorkItem { [weak self] in
            guard let self, !self.open, !self.suppressHover, !self.dragging else { return }
            self.show()
        }
        hoverWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.18, execute: work)
    }

    func click(at point: NSPoint) {
        guard open, !dragging, !resizing, !modalInteraction else { return }
        if panel.frame.contains(point) {
            clickedInside = true
        } else if !handle.frame.contains(point), !clickedInside, !pinned {
            hide()
        }
    }

    func drag(to point: NSPoint, ended: Bool, fromHeader: Bool) {
        if closed || resizing || pendingClose != nil { return }
        if !dragging {
            guard !ended else { return }
            dragging = true
            dragStart = point
            panelAtDragStart = panel.frame
            handleAtDragStart = handle.frame
            dragWasOpen = open || fromHeader
            hoverWork?.cancel()
            animationToken += 1
            if !dragWasOpen { panel.orderOut(nil) }
            if fromHeader { clickedInside = true }
            return
        }
        let dx = point.x - dragStart.x
        let dy = point.y - dragStart.y
        handle.setFrameOrigin(NSPoint(x: handleAtDragStart.minX + dx,
                                      y: handleAtDragStart.minY + dy))
        if dragWasOpen {
            panel.setFrameOrigin(NSPoint(x: panelAtDragStart.minX + dx,
                                         y: panelAtDragStart.minY + dy))
        }
        if ended { snap(to: point) }
    }

    private func snap(to point: NSPoint) {
        lastDragEnded = Date()
        screen = Self.screen(containing: point)
        updateSize()
        rightSide = point.x >= screen.midX
        handleY = min(max(handle.frame.minY, screen.minY), screen.maxY - 96)
        panelY = min(max(handleY - (panelHeight - 96) / 2, screen.minY),
                     screen.maxY - panelHeight)
        open = dragWasOpen
        handleState.rightSide = rightSide
        windowLayout.rightSide = rightSide
        handleState.isOpen = open
        windowLayout.isDrawerOpen = open
        let fraction = (handleY - screen.minY) / max(1, screen.height - 96)
        onMoved(DrawerPosition(screenX: screen.minX, screenY: screen.minY,
                               screenWidth: screen.width, screenHeight: screen.height,
                               rightSide: rightSide, verticalFraction: fraction))
        dragging = false
        animate(open: open, duration: 0.22) { [weak self] in
            guard let self else { return }
            if !self.open, self.pinned { self.show() }
        }
    }

    private func animate(open targetOpen: Bool, duration: TimeInterval,
                         completion: (() -> Void)? = nil) {
        animationToken += 1
        let token = animationToken
        let panelFrame = targetPanelFrame(open: targetOpen)
        let handleFrame = targetHandleFrame()
        NSAnimationContext.runAnimationGroup { context in
            context.duration = duration
            context.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
            panel.animator().setFrame(panelFrame, display: true)
            handle.animator().setFrame(handleFrame, display: true)
        } completionHandler: { [weak self] in
            Task { @MainActor in
                guard let self, token == self.animationToken else { return }
                if !self.open {
                    self.finishClose()
                }
                completion?()
            }
        }
    }

    private func finishClose() {
        animationToken += 1
        open = false
        handleState.isOpen = false
        windowLayout.isDrawerOpen = false
        panel.orderOut(nil)
        let action = pendingClose
        pendingClose = nil
        if !closed { action?() }
    }

    private func positionWindows(open shown: Bool) {
        panel.setFrame(targetPanelFrame(open: shown), display: true)
        handle.setFrame(targetHandleFrame(), display: true)
        handle.alphaValue = 0.66
    }

    private func targetPanelFrame(open shown: Bool) -> NSRect {
        let x = rightSide ? (shown ? screen.maxX - panelWidth - 18 : screen.maxX)
                          : (shown ? screen.minX + 18 : screen.minX - panelWidth)
        return NSRect(x: x, y: panelY, width: panelWidth, height: panelHeight)
    }

    private func targetHandleFrame() -> NSRect {
        let x = rightSide ? screen.maxX - 18 : screen.minX
        return NSRect(x: x, y: handleY, width: 18, height: 96)
    }

    private func updateSize() {
        panelWidth = max(1, min(desiredWidth, screen.width - 18))
        panelHeight = max(1, min(desiredHeight, screen.height - 24))
        windowLayout.width = panelWidth
        windowLayout.height = panelHeight
    }

    private func adjustVerticalPosition() {
        handleY = min(max(handleY, screen.minY), screen.maxY - 96)
        panelY = min(max(handleY - (panelHeight - 96) / 2, screen.minY),
                     screen.maxY - panelHeight)
    }

    private static func makePanel(width: CGFloat, height: CGFloat) -> NSPanel {
        let window = NSPanel(contentRect: NSRect(x: 0, y: 0, width: width, height: height),
                             styleMask: [.borderless, .nonactivatingPanel],
                             backing: .buffered, defer: false)
        window.backgroundColor = .clear
        window.isOpaque = false
        window.level = .floating
        window.hidesOnDeactivate = false
        window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        return window
    }

    private static func screen(containing point: NSPoint) -> NSRect {
        if let match = NSScreen.screens.first(where: { $0.frame.contains(point) }) {
            return match.visibleFrame
        }
        return NSScreen.main?.visibleFrame ?? NSScreen.screens.first?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
    }

    private static func savedScreen(for position: DrawerPosition) -> NSRect {
        let savedCenter = NSPoint(x: position.screenX + position.screenWidth / 2,
                                  y: position.screenY + position.screenHeight / 2)
        let exact = NSScreen.screens.first { candidate in
            let frame = candidate.visibleFrame
            return abs(frame.minX - position.screenX) < 2
                && abs(frame.minY - position.screenY) < 2
                && abs(frame.width - position.screenWidth) < 2
                && abs(frame.height - position.screenHeight) < 2
        }
        if let exact { return exact.visibleFrame }
        let closest = NSScreen.screens.min { first, second in
            let a = first.visibleFrame, b = second.visibleFrame
            let da = pow(a.midX - savedCenter.x, 2) + pow(a.midY - savedCenter.y, 2)
            let db = pow(b.midX - savedCenter.x, 2) + pow(b.midY - savedCenter.y, 2)
            return da < db
        }
        return (closest ?? NSScreen.main ?? NSScreen.screens.first)?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1024, height: 768)
    }
}
