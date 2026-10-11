import java.nio.file.*;
import java.util.*;

public class SettingsPolicyTests {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); Files.createDirectories(root);
        TestSupport.run("settings.defaults", () -> {
            var settings = AppSettings.load(root.resolve("defaults"));
            TestSupport.check(settings.equals(new AppSettings(40, false, "light", 4)), "Wrong defaults");
        });
        TestSupport.run("settings.roundtrip.theme.drawer-pin.notice", () -> {
            Path directory = root.resolve("roundtrip");
            for (String theme : List.of("dark", "light")) for (boolean pin : List.of(false, true)) {
                var value = new AppSettings(7, pin, theme, 0); value.save(directory);
                TestSupport.check(value.equals(AppSettings.load(directory)), "Settings lost");
                AppSettings.savePinned(directory, !pin);
                TestSupport.check(AppSettings.load(directory).equals(new AppSettings(7, !pin, theme, 0)), "Pin changed other settings");
            }
        });
        TestSupport.run("settings.invalid-input.preserved", () -> {
            Path directory = root.resolve("bad"); Files.createDirectories(directory);
            for (String bad : List.of("[]", "null", "{", "{\"theme\":\"blue\"}", "{\"historyLimit\":1.5}",
                    "{\"pinned\":\"true\"}", "{\"sizeNoticeSeconds\":1}", "{\"historyLimit\":-1}")) {
                Files.writeString(directory.resolve("settings.json"), bad);
                TestSupport.rejects(() -> AppSettings.load(directory));
                TestSupport.check(Files.readString(directory.resolve("settings.json")).equals(bad), "Invalid settings overwritten");
            }
        });
        TestSupport.run("history.acceptance.boundaries.whitespace", () -> {
            for (String value : List.of("", " \n\t", "\u2003")) TestSupport.check(!HistoryPolicy.accepts(value), "Blank accepted");
            for (String value : List.of("\u00a0", "a", "😀", "x".repeat(65536))) TestSupport.check(HistoryPolicy.accepts(value), "Valid text rejected");
            TestSupport.check(!HistoryPolicy.accepts(null) && !HistoryPolicy.accepts("x".repeat(65537)), "Limit violated");
        });
        TestSupport.run("history.pins.order.eviction.unpin.delete", () -> {
            var pins = List.of("p1", "p2");
            TestSupport.check(HistoryPolicy.withPins(List.of("new", "p1", "old"), pins, 1).equals(List.of("p1", "p2", "new")), "Pin/count policy");
            TestSupport.check(HistoryPolicy.withPins(List.of("p1", "p2", "new"), List.of("p2"), 1).equals(List.of("p2", "p1")), "Unpin policy");
            TestSupport.check(HistoryPolicy.withPins(List.of("new"), List.of(), 1).equals(List.of("new")), "Delete policy");
        });
        TestSupport.run("history.write-failure.preserves-destination", () -> {
            Path directory = root.resolve("failed-write"); Files.createDirectories(directory.resolve("history.properties"));
            Files.writeString(directory.resolve("history.properties/keep"), "untouched");
            TestSupport.rejects(() -> new HistoryStore(directory, 4).save(List.of("test")));
            TestSupport.check(Files.readString(directory.resolve("history.properties/keep")).equals("untouched"), "Existing data changed");
            try (var files = Files.list(directory)) { TestSupport.check(files.noneMatch(p -> p.toString().endsWith(".tmp")), "Failed write leaked temp file"); }
        });
        TestSupport.run("size.factory.save-default.reset.boundaries", () -> {
            var store = new WindowSizeStore(root.resolve("sizes"));
            TestSupport.check(store.load().equals(WindowSizeStore.factory()), "Factory size");
            var saved = store.load().withCurrent(500, 260).saveCurrentAsDefault();
            store.save(saved.withCurrent(4096, 2160));
            TestSupport.check(store.load().resetToDefault().equals(saved), "Default not retained");
            TestSupport.rejects(() -> saved.withCurrent(499, 260)); TestSupport.rejects(() -> saved.withCurrent(500, 2161));
        });
        TestSupport.run("size.corruption-and-symlink", () -> {
            Path directory = root.resolve("bad-size"); Files.createDirectories(directory);
            Path file = directory.resolve("window-size.properties"); Files.writeString(file, "width=NaN\n");
            TestSupport.rejects(() -> new WindowSizeStore(directory).load());
            Files.delete(file); Path target = directory.resolve("target"); Files.writeString(target, "safe"); Files.createSymbolicLink(file, target);
            TestSupport.rejects(() -> new WindowSizeStore(directory).save(WindowSizeStore.factory()));
            TestSupport.check(Files.readString(target).equals("safe"), "Symlink target modified");
        });
        TestSupport.run("migration.copies-once.private.preserves-legacy", () -> {
            String oldHome = System.getProperty("user.home");
            Path home = root.resolve("migration"); Path legacy = home.resolve(".mcopier"); Files.createDirectories(legacy);
            Files.writeString(legacy.resolve("history.properties"), "legacy");
            Files.writeString(legacy.resolve("ignored.txt"), "not migrated");
            try {
                System.setProperty("user.home", home.toString()); Path current = DataDirectory.prepare();
                TestSupport.check(Files.readString(current.resolve("history.properties")).equals("legacy"), "Migration missing");
                TestSupport.check(Files.exists(legacy.resolve("history.properties")) && !Files.exists(current.resolve("ignored.txt")), "Migration scope");
                Files.writeString(current.resolve("history.properties"), "new"); DataDirectory.prepare();
                TestSupport.check(Files.readString(current.resolve("history.properties")).equals("new"), "Repeated migration overwrote data");
            } finally { System.setProperty("user.home", oldHome); }
        });
        TestSupport.finish();
    }
}
