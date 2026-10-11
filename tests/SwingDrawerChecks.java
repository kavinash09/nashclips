import java.awt.Point;
import java.awt.Window;
import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.*;

public class SwingDrawerChecks {
    static final java.util.List<EdgeDrawer> drawers = new java.util.ArrayList<>();
    record Fixture(JFrame panel, EdgeDrawer drawer, SizeNotice notice) { }
    static <T> T ui(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        try {
            for (int i = 0; i < 12; i++) {
                Fixture f = ui(() -> {
                    JFrame panel = new JFrame();
                    panel.setUndecorated(true); panel.setFocusableWindowState(false); panel.setOpacity(0);
                    EdgeDrawer drawer = new EdgeDrawer(panel);
                    drawers.add(drawer);
                    SizeNotice notice = new SizeNotice(); panel.setGlassPane(notice);
                    for (Window window : Window.getWindows())
                        if (!(window instanceof java.awt.Frame frame) || frame.isUndecorated()) window.setOpacity(0);
                    drawer.showDrawer();
                    return new Fixture(panel, drawer, notice);
                });
                Thread.sleep(400);
                ui(() -> {
                    check(f.panel.isShowing(), "Show failed");
                    f.drawer.onGlobalClick(new Point(-100000, -100000)); return null;
                });
                Thread.sleep(400);
                ui(() -> {
                    check(!f.panel.isShowing(), "Outside click did not hide");
                    f.drawer.showDrawer(); return null;
                });
                Thread.sleep(400);
                ui(() -> {
                    f.drawer.onGlobalClick(new Point(f.panel.getX() + 30, f.panel.getY() + 30));
                    f.drawer.onGlobalClick(new Point(-100000, -100000));
                    return null;
                });
                Thread.sleep(500);
                ui(() -> {
                    check(f.panel.isShowing(), "Click-inside latch lost after animation settled");
                    f.drawer.beginResize(EdgeDrawer.ResizeEdge.TOP, new Point(f.panel.getX(), f.panel.getY()));
                    f.drawer.hideImmediately(); // Use can arrive while resizing.
                    f.drawer.showDrawer();
                    check(f.panel.isShowing(), "Interrupted resize stranded drawer");
                    f.notice.showSize(80000, 0); f.notice.close(); f.notice.showSize(80000, 0);
                    check(!f.notice.isVisible(), "Closed notice restarted");
                    f.drawer.close(); f.drawer.close();
                    f.drawer.hideImmediately(); f.drawer.showDrawer(); f.drawer.resize(650, 300);
                    for (String name : new String[]{"hoverDelay", "animation", "snapAnimation", "pointerWatcher"}) {
                        Field field = EdgeDrawer.class.getDeclaredField(name); field.setAccessible(true);
                        check(!((Timer) field.get(f.drawer)).isRunning(), "Timer left running: " + name);
                    }
                    return null;
                });
                Thread.sleep(200);
                ui(() -> {
                    for (Window window : Window.getWindows())
                        check(!window.isDisplayable(), "Native window survived close or was recreated");
                    return null;
                });
            }
            System.out.println("PASS: 12 Swing drawer lifecycles, click latch, interrupted resize, late calls after close; all timers stopped and windows disposed");
        } finally {
            ui(() -> {
                drawers.forEach(EdgeDrawer::close);
                for (Window window : Window.getWindows()) window.dispose();
                return null;
            });
        }
    }
}
