package nashclip;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.awt.Point;

public class DrawerChecks {
    record Fixture(Stage panel, BorderPane root, EdgeDrawer drawer) { }
    static <T> T ui(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task); return task.get(10, TimeUnit.SECONDS);
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Platform.startup(() -> Platform.setImplicitExit(false));
        try {
            for (int i = 0; i < 12; i++) {
                Fixture f = ui(() -> {
                    Stage panel = new Stage(StageStyle.TRANSPARENT);
                    BorderPane root = new BorderPane();
                    HBox header = new HBox(new Button("test")); root.setTop(header);
                    panel.setScene(new Scene(root, 760, 340)); panel.setOpacity(0);
                    EdgeDrawer drawer = new EdgeDrawer(panel, header, false, null, 760, 340, p -> { });
                    for (Window w : Window.getWindows()) w.setOpacity(0);
                    drawer.showDrawer();
                    return new Fixture(panel, root, drawer);
                });
                Thread.sleep(400);
                ui(() -> {
                    check(f.panel.isShowing(), "Show failed");
                    f.drawer.onGlobalClick(new Point(-100000, -100000)); return null;
                });
                Thread.sleep(400);
                ui(() -> { check(!f.panel.isShowing(), "Outside click failed"); f.drawer.showDrawer(); return null; });
                Thread.sleep(400);
                AtomicInteger completions = new AtomicInteger();
                ui(() -> {
                    f.drawer.onGlobalClick(new Point((int) f.panel.getX() + 30, (int) f.panel.getY() + 30));
                    f.drawer.onGlobalClick(new Point(-100000, -100000));
                    return null;
                });
                Thread.sleep(500);
                ui(() -> {
                    check(f.panel.isShowing(), "Click-inside latch lost after animation settled");
                    check(f.drawer.hideDrawer(completions::incrementAndGet), "Hide rejected");
                    f.drawer.resize(650, 300); // Used to cancel the close callback and strand Use.
                    return null;
                });
                Thread.sleep(400);
                ui(() -> {
                    check(completions.get() == 1 && !f.panel.isShowing(), "Resize stranded/repeated close action");
                    f.drawer.showDrawer();
                    f.drawer.beginResize(EdgeDrawer.ResizeEdge.TOP, f.panel.getX() + 50, f.panel.getY());
                    check(!f.drawer.hideDrawer(completions::incrementAndGet), "Busy close should be refused explicitly");
                    f.drawer.endResize(f.panel.getX() + 50, f.panel.getY());
                    SizeNotice notice = new SizeNotice(f.panel, f.root);
                    notice.showSize(80000, 0);
                    notice.close();
                    check(!f.root.getChildren().contains(notice), "Notice left attached after close");
                    f.drawer.hideDrawer(completions::incrementAndGet);
                    f.drawer.close(); f.drawer.close(); f.drawer.showDrawer();
                    check(!f.panel.isShowing(), "Closed drawer resurrected");
                    return null;
                });
                Thread.sleep(400);
                check(completions.get() == 1, "Close delivered stale paste callback");
                ui(() -> { check(Window.getWindows().isEmpty(), "Window leaked after close"); return null; });
            }
            System.out.println("PASS: 12 JavaFX drawer lifecycles, outside/inside clicks, resize-during-hide, busy refusal, overlay detach, shutdown callbacks; zero open windows");
        } finally { Platform.exit(); }
    }
}
