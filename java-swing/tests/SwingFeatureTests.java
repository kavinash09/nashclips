import java.awt.*;
import java.awt.datatransfer.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;

public class SwingFeatureTests {
    static Path root;
    static <T> T ui(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
    static AbstractButton button(Container root, String prefix) {
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton b && b.getAccessibleContext().getAccessibleName() != null
                    && b.getAccessibleContext().getAccessibleName().startsWith(prefix)) return b;
            if (child instanceof Container c) { AbstractButton found = button(c, prefix); if (found != null) return found; }
        }
        return null;
    }
    static final class Fixture implements AutoCloseable {
        final NashClip app; final DefaultListModel<String> history; final HistoryGrid grid;
        final JFrame panel; final EdgeDrawer drawer; final Clipboard board = new Clipboard("test");
        final PasteServiceTests.Platform platform = new PasteServiceTests.Platform();
        final Path data;
        @SuppressWarnings("unchecked") Fixture() throws Exception {
            Path home = Files.createTempDirectory(root, "home-");
            System.setProperty("user.home", home.toString()); data = home.resolve(".nashclip");
            app = new NashClip();
            ((PasteService) TestSupport.get(app, "pasteService")).close();
            TestSupport.set(app, "pasteService", new PasteService(platform));
            history = (DefaultListModel<String>) TestSupport.call(app, "loadHistoryModel", new Class<?>[0]);
            TestSupport.set(app, "historyModel", history); TestSupport.set(app, "statusLabel", new JLabel());
            grid = new HistoryGrid(history, i -> { try { return tile(i); } catch (Exception e) { throw new RuntimeException(e); } });
            TestSupport.set(app, "historyGrid", grid);
            var watcher = new ClipboardWatcher(board, s -> { }, n -> { }); TestSupport.set(app, "clipboardWatcher", watcher);
            panel = new JFrame(); panel.setUndecorated(true); panel.setFocusableWindowState(false); panel.setOpacity(0);
            drawer = new EdgeDrawer(panel); TestSupport.set(app, "frame", panel); TestSupport.set(app, "edgeDrawer", drawer);
            for (Window w : Window.getWindows()) if (!(w instanceof Frame f) || f.isUndecorated()) w.setOpacity(0);
        }
        JPanel tile(int i) throws Exception { return (JPanel) TestSupport.call(app, "createHistoryTile", new Class<?>[]{int.class}, i); }
        void add(String text) throws Exception { TestSupport.call(app, "addCopiedText", new Class<?>[]{String.class}, text); }
        @SuppressWarnings("unchecked") List<String> pins() throws Exception { return (List<String>) TestSupport.get(app, "pinnedClips"); }
        public void close() throws Exception {
            TestSupport.set(app, "stopped", true); drawer.close();
            ((ClipboardWatcher) TestSupport.get(app, "clipboardWatcher")).stop();
            ((PasteService) TestSupport.get(app, "pasteService")).close();
            ((InstanceLock) TestSupport.get(app, "instanceLock")).close();
        }
    }
    public static void main(String[] args) throws Exception {
        root = Path.of(args[0]); Files.createDirectories(root);
        try {
            ui(() -> {
                TestSupport.run("swing.tiles.pin-five.unpin.delete.persist", () -> {
                    try (var f = new Fixture()) {
                        for (int i=0;i<6;i++) f.add("clip"+i);
                        for (int i=0;i<5;i++) button(f.tile(5), "Pin this clip").doClick(0);
                        TestSupport.check(f.pins().size()==5, "Five pins missing");
                        button(f.tile(5), "Pin this clip").doClick(0);
                        TestSupport.check(f.pins().size()==5, "Sixth pin accepted");
                        var saved = new HistoryStore(f.data,40).loadSnapshot();
                        TestSupport.check(saved.pinned().equals(f.pins()), "Pin restart data");
                        String deleted = f.history.get(0); button(f.tile(0), "Delete clip").doClick(0);
                        TestSupport.check(!f.pins().contains(deleted) && !f.history.contains(deleted), "Pinned delete failed");
                        button(f.tile(0), "Unpin this clip").doClick(0);
                        TestSupport.check(f.pins().size()==3, "Unpin failed");
                        TestSupport.check(new HistoryStore(f.data,40).loadSnapshot().pinned().size()==3, "Unpin not saved");
                    }
                });
                TestSupport.run("swing.tiles.failed-save-keeps-model", () -> {
                    try (var f = new Fixture()) {
                        f.add("keep"); Path file=f.data.resolve("history.properties"); Files.delete(file); Files.createDirectory(file); Files.writeString(file.resolve("block"),"x");
                        button(f.tile(0), "Delete clip").doClick(0);
                        TestSupport.check(f.history.size()==1 && f.history.get(0).equals("keep"), "Failed deletion changed model");
                        button(f.tile(0), "Pin this clip").doClick(0);
                        TestSupport.check(f.pins().isEmpty(), "Failed pin changed model");
                    }
                });
                TestSupport.run("swing.capture.newest-first.limit-and-pinned-dedup", () -> {
                    try (var f = new Fixture()) {
                        f.add("first"); button(f.tile(0), "Pin this clip").doClick(0);
                        TestSupport.set(f.app,"historyLimit",2);
                        f.add("second"); f.add("third"); f.add("fourth"); f.add("first");
                        TestSupport.check(java.util.Collections.list(f.history.elements()).equals(List.of("first","fourth","third")), "Ordering/limit mismatch");
                    }
                });
                TestSupport.run("swing.layout.shortest-column.responsive.empty", () -> {
                    var model=new DefaultListModel<String>(); for(String s:List.of("a","b","c","d")) model.addElement(s);
                    var grid=new HistoryGrid(model,i->{var tile=new JPanel();tile.setName(model.get(i));tile.setPreferredSize(new Dimension(220,i==1?40:200));return tile;});
                    grid.setAvailableWidth(900); Container columns=(Container)grid.getComponent(0);
                    TestSupport.check(columns.getComponentCount()==3,"Not three columns");
                    Container middle=(Container)columns.getComponent(1);
                    TestSupport.check(middle.getComponent(2).getName().equals("d"),"D should fill below B");
                    grid.setAvailableWidth(200); TestSupport.check(((Container)grid.getComponent(0)).getComponentCount()==1,"Narrow layout");
                    model.clear(); grid.refresh(); TestSupport.check(grid.getComponentCount()==1,"Empty view missing");
                });
                TestSupport.run("swing.theme.roundtrip-and-pin-colour", () -> {
                    ClipboardTheme.use("light"); Color light=ClipboardTheme.BACKGROUND;
                    TestSupport.check(!ClipboardTheme.PIN_ACTIVE.equals(ClipboardTheme.ACCENT),"Pinned icon lacks distinct shade");
                    ClipboardTheme.use("dark"); TestSupport.check(!light.equals(ClipboardTheme.BACKGROUND),"Theme unchanged");
                    ClipboardTheme.use("light"); TestSupport.check(light.equals(ClipboardTheme.BACKGROUND),"Theme not reversible");
                });
                return null;
            });
            TestSupport.run("swing.use-button.copies-full-text.hides.delivers-once", () -> {
                Fixture f=ui(Fixture::new);
                try {
                    String text="full text "+"x".repeat(1200); f.platform.clipboard=text;
                    ui(()->{f.drawer.showDrawer(); return null;});
                    Thread.sleep(500);
                    ui(()->{TestSupport.check(f.panel.isVisible(), "Use precondition: panel must be open"); return null;});
                    ui(()->{f.add(text);button(f.tile(0),"Use clip").doClick(0);return null;});
                    long end=System.nanoTime()+5_000_000_000L;
                    while(ui(()->(boolean)TestSupport.get(f.app,"useInProgress")) && System.nanoTime()<end) Thread.sleep(20);
                    ui(()->{TestSupport.check(f.platform.sends==1,"Paste adapter not called once");
                        TestSupport.check(f.board.getData(DataFlavor.stringFlavor).equals(text),"Use copied truncated text");
                        TestSupport.check(!f.panel.isVisible(),"Use did not hide panel");return null;});
                } finally {ui(()->{f.close();return null;});}
            });
            TestSupport.finish();
        } finally {ui(()->{for(Window w:Window.getWindows())w.dispose();return null;});}
    }
}
