import java.nio.file.*;
import java.util.*;

/** Adversarial inputs and independent retention invariants; all data is disposable. */
public class DataValidationTests {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); Files.createDirectories(root);
        TestSupport.run("storage.malformed-history.preserved", () -> {
            Path directory = root.resolve("bad-history"); Files.createDirectories(directory);
            Path file = directory.resolve("history.properties");
            var store = new HistoryStore(directory, 40);
            for (String bad : List.of("", "version=3\ncount=0", "version=1", "version=1\ncount=-1",
                    "version=1\ncount=no", "version=1\ncount=1", "version=2\ncount=0",
                    "version=2\ncount=0\npinned.count=no", "version=2\ncount=0\npinned.count=-1",
                    "version=2\ncount=0\npinned.count=6", "version=2\ncount=0\npinned.count=1",
                    "version=2\ncount=0\npinned.count=1\npinned.0=   ",
                    "version=2\ncount=0\npinned.count=2\npinned.0=a\npinned.1=a",
                    "version=1\ncount=1\nentry.0=\\uZZZZ",
                    "version=1\ncount=1\nentry.0=" + "a".repeat(65537),
                    "version=2\ncount=0\npinned.count=1\npinned.0=" + "a".repeat(65537))) {
                Files.writeString(file, bad);
                TestSupport.rejects(store::loadSnapshot);
                TestSupport.check(Files.readString(file).equals(bad), "Bad history was rewritten");
            }
            TestSupport.rejects(() -> new HistoryStore(directory, 0));
            TestSupport.rejects(() -> new HistoryStore(directory, 501));
        });
        TestSupport.run("settings.duplicate-types-and-boundaries", () -> {
            for (String field : List.of("\"historyLimit\":1", "\"pinned\":true", "\"theme\":\"dark\"", "\"sizeNoticeSeconds\":4")) {
                TestSupport.rejects(() -> AppSettings.parse("{" + field + "," + field + "}"));
                TestSupport.rejects(() -> AppSettings.parse("{" + field + ",}"));
            }
            for (String bad : List.of("{\"historyLimit\":0}", "{\"historyLimit\":501}",
                    "{\"historyLimit\":999999999999999999999}", "{\"historyLimit\":true}",
                    "{\"pinned\":1}", "{\"theme\":true}", "{\"sizeNoticeSeconds\":false}",
                    "{\"sizeNoticeSeconds\":6}")) TestSupport.rejects(() -> AppSettings.parse(bad));
            for (int limit : new int[]{1, 500}) for (int seconds : new int[]{0, 2, 3, 4, 5}) {
                var value = AppSettings.parse("{\"historyLimit\":" + limit + ",\"sizeNoticeSeconds\":" + seconds + "}");
                TestSupport.check(value.historyLimit() == limit && value.sizeNoticeSeconds() == seconds, "Boundary lost");
            }
            TestSupport.rejects(() -> new AppSettings(0));
            TestSupport.rejects(() -> new AppSettings(501, true));
            TestSupport.rejects(() -> new AppSettings(40, false, "blue"));
            TestSupport.rejects(() -> new AppSettings(40, false, "light", -1));
            TestSupport.rejects(() -> new AppSettings(40, false, "light", 6));
        });
        TestSupport.run("storage.symlinks.read-write-lock.protect-target", () -> {
            Path directory = root.resolve("links"); Files.createDirectories(directory);
            Path target = directory.resolve("target"); Files.writeString(target, "untouched");
            for (String name : List.of("history.properties", "settings.json", "instance.lock"))
                Files.createSymbolicLink(directory.resolve(name), target);
            var store = new HistoryStore(directory, 4);
            TestSupport.rejects(store::load);
            TestSupport.rejects(() -> store.save(List.of("overwrite")));
            TestSupport.rejects(() -> AppSettings.load(directory));
            TestSupport.rejects(() -> new AppSettings(4).save(directory));
            TestSupport.rejects(() -> new InstanceLock(directory));
            Path alias = root.resolve("directory-link"); Files.createSymbolicLink(alias, directory);
            TestSupport.rejects(() -> new HistoryStore(alias, 4).load());
            TestSupport.rejects(() -> AppSettings.load(alias));
            TestSupport.rejects(() -> new InstanceLock(alias));
            TestSupport.check(Files.readString(target).equals("untouched"), "Symlink target changed");
        });
        TestSupport.run("lock.overlap.close-idempotent.reacquire", () -> {
            Path directory = root.resolve("lock");
            try (var lock = new InstanceLock(directory)) {
                TestSupport.rejects(() -> new InstanceLock(directory));
                lock.close(); lock.close();
                try (var next = new InstanceLock(directory)) { TestSupport.check(Files.exists(directory.resolve("instance.lock")), "Lock inode deleted"); }
            }
        });
        TestSupport.run("history.seeded-retention-and-roundtrip-invariants", () -> {
            var five = List.of("p1", "p2", "p3", "p4", "p5");
            TestSupport.check(HistoryPolicy.withPins(List.of(), five, 1).equals(five), "Five pins rejected");
            TestSupport.rejects(() -> HistoryPolicy.withPins(List.of(), List.of("p1", "p2", "p3", "p4", "p5", "p6"), 1));
            Random random = new Random(734981);
            for (int trial = 0; trial < 80; trial++) {
                int limit = 1 + random.nextInt(40), pinCount = random.nextInt(6);
                var pins = new ArrayList<String>();
                for (int i = 0; i < pinCount; i++) pins.add("pin-" + i + "😀");
                var entries = new ArrayList<String>();
                for (int i = 0; i < 70; i++) entries.add(switch (random.nextInt(6)) {
                    case 0 -> " \t\n"; case 1 -> "oversize".repeat(9000);
                    case 2 -> pins.isEmpty() ? "é" : pins.get(0);
                    default -> "clip-" + i + " e\u0301 😀 \\ \n\t=";
                });
                var result = HistoryPolicy.withPins(entries, pins, limit);
                TestSupport.check(result.subList(0, pins.size()).equals(pins), "Pins lost order/priority");
                var regular = result.subList(pins.size(), result.size());
                TestSupport.check(regular.size() <= limit && result.stream().mapToInt(String::length).sum() <= 1_048_576, "Retention exceeded budget");
                TestSupport.check(result.stream().allMatch(t -> !t.isBlank() && t.length() <= 65_536), "Invalid text retained");
                TestSupport.check(regular.stream().noneMatch(pins::contains), "Pinned duplicate retained");
                int cursor = 0;
                for (String value : regular) {
                    while (cursor < entries.size() && !entries.get(cursor).equals(value)) cursor++;
                    TestSupport.check(cursor++ < entries.size(), "Retention reordered/invented text");
                }
                TestSupport.check(HistoryPolicy.withPins(result, pins, limit).equals(result), "Retention not idempotent");
                var store = new HistoryStore(root.resolve("roundtrip"), limit);
                store.saveSnapshot(new HistoryStore.Snapshot(entries, pins));
                TestSupport.check(store.loadSnapshot().equals(new HistoryStore.Snapshot(result, pins)), "UTF-16 roundtrip lost data");
            }
            // This independent oracle catches dropping everything while satisfying the invariants above.
            TestSupport.check(HistoryPolicy.withPins(List.of("a", "b", "c"), List.of(), 2).equals(List.of("a", "b")), "Newest ordinary clips not retained");
        });
        TestSupport.finish();
    }
}
