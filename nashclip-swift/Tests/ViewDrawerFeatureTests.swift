import AppKit
import SwiftUI

@main struct ViewDrawerFeatureTests {
    @MainActor static func main() throws {
        _ = NSApplication.shared; NSApp.setActivationPolicy(.accessory)
        let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory:true)
        let board = NSPasteboard.withUniqueName(); defer { board.releaseGlobally() }
        let store = CopierStorage(directory:root); try store.saveSettings(CopierSettings(sizeNoticeSeconds:2))
        let model = ClipboardModel(storage:store,pasteboard:board); model.start(); defer { model.stop() }
        let layout = WindowLayoutState(width:760,height:340)
        let view = CopierView(model:model,windowLayout:layout,useClip:{_ in},deleteClip:{_ in},togglePin:{},toggleTheme:{},hideWindow:{},quitApp:{},changeSize:{},dragHeader:{_,_ in},dragResize:{_,_,_ in})
        var positions:[DrawerPosition] = []; var sizes:[NSSize] = []
        let drawer = DrawerController(view:view,windowLayout:layout,size:WindowSize(),pinned:false,dark:false,savedPosition:nil,onMoved:{positions.append($0)},onResized:{sizes.append(NSSize(width:$0,height:$1))})
        drawer.panel.alphaValue = 0; drawer.handle.alphaValue = 0; defer { drawer.close() }
        drawer.panel.ignoresMouseEvents = true; drawer.handle.ignoresMouseEvents = true
        func wait(_ seconds:Double) { RunLoop.current.run(until:Date(timeIntervalSinceNow:seconds)) }
        TestSupport.run("swift.layout.shortest-column.responsive.empty.theme") {
            let clips = [Clip(text:String(repeating:"a\n",count:20)),Clip(text:"b"),Clip(text:String(repeating:"c\n",count:20)),Clip(text:"d")]
            let columns = view.masonryColumns(clips)
            try TestSupport.check(columns.count == 3 && columns[1].map(\.text) == ["b","d"],"D should fill below B")
            layout.width = 200; try TestSupport.check(view.masonryColumns(clips).count == 1,"Narrow layout")
            try TestSupport.check(view.masonryColumns([]).allSatisfy(\.isEmpty),"Empty layout")
            try TestSupport.check(Palette(dark:false).background != Palette(dark:true).background,"Themes identical")
            layout.width = 760
        }
        TestSupport.run("swift.drawer.delayed-hover.cancel.hover-out-keeps-open") {
            drawer.hover(true);wait(0.05);try TestSupport.check(!drawer.panel.isVisible,"Hover opened immediately")
            drawer.hover(false);wait(0.3);try TestSupport.check(!drawer.panel.isVisible,"Cancelled hover opened")
            drawer.hover(true);wait(0.6);try TestSupport.check(drawer.panel.isVisible,"Hover failed")
            drawer.hover(false);wait(0.4);try TestSupport.check(drawer.panel.isVisible,"Hover-out closed drawer")
        }
        TestSupport.run("swift.drawer.header-drag.docking.position-and-arrow") {
            let screen = NSScreen.main!.visibleFrame
            for right in [false,true] {
                let start=NSPoint(x:drawer.panel.frame.midX,y:drawer.panel.frame.maxY-20)
                let end=NSPoint(x:right ? screen.maxX-60:screen.minX+60,y:screen.midY)
                drawer.drag(to:start,ended:false,fromHeader:true);drawer.drag(to:end,ended:true,fromHeader:true);wait(0.35)
                try TestSupport.check(positions.last?.rightSide == right && layout.rightSide == right,"Dock callback")
                try TestSupport.check(right ? drawer.handle.frame.minX >= drawer.panel.frame.maxX-1 : drawer.handle.frame.maxX <= drawer.panel.frame.minX+1,"Arrow is not outermost")
            }
        }
        TestSupport.run("swift.drawer.pin.modal.resize-save") {
            drawer.setPinned(true);drawer.click(at:NSPoint(x:-100000,y:-100000));wait(0.4)
            try TestSupport.check(drawer.panel.isVisible,"Pinned auto-closed")
            drawer.setPinned(false);drawer.setModalInteraction(true);drawer.click(at:NSPoint(x:-100000,y:-100000));wait(0.4)
            try TestSupport.check(drawer.panel.isVisible,"Modal auto-closed");drawer.setModalInteraction(false)
            let width=drawer.panel.frame.width;let point=NSPoint(x:drawer.panel.frame.minX,y:drawer.panel.frame.midY)
            drawer.dragResize(edge:.left,to:point,ended:false);drawer.dragResize(edge:.left,to:NSPoint(x:point.x+80,y:point.y),ended:true)
            try TestSupport.check(drawer.panel.frame.width < width && sizes.count == 1,"Resize/save callback")
        }
        TestSupport.run("swift.notice.hidden-deferral.visible-expiry.manual-dismiss") {
            drawer.hide();wait(0.4)
            board.clearContents();board.setString(String(repeating:"x",count:65537),forType:.string);model.checkClipboard();wait(2.3)
            try TestSupport.check(model.rejectedClip != nil,"Hidden notice expired")
            drawer.show();wait(2.7);try TestSupport.check(model.rejectedClip == nil,"Visible notice did not expire")
            try store.saveSettings(CopierSettings(sizeNoticeSeconds:0));try model.updateSettings(limit:40,pinned:false,theme:"light")
            board.clearContents();board.setString(String(repeating:"y",count:65537),forType:.string);model.checkClipboard();wait(2.3)
            try TestSupport.check(model.rejectedClip != nil,"Manual notice expired");model.dismissSizeNotice()
            try TestSupport.check(model.rejectedClip == nil,"Manual dismiss failed")
        }
        try TestSupport.finish()
    }
}
