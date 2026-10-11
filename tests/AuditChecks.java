import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.io.IOException;

/** Runs without opening windows, reading the clipboard, or sending keyboard events. */
public class AuditChecks {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static void rejected(Throwing action) throws Exception {
        try { action.run(); } catch (IOException | IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejection");
    }
    interface Throwing { void run() throws Exception; }
    static final List<String> SAMPLE = List.of("é", "e\u0301", "\u00a0", " leading space\r\nnext\tline\\end=:# !", "emoji 😀 👨‍👩‍👧", "a\u2028b\u2029c", "nul\0end");

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[1]);
        if (args[0].equals("hold")) {
            try (InstanceLock lock = new InstanceLock(directory)) {
                System.out.println("LOCKED"); System.out.flush();
                System.in.read();
            }
            return;
        }
        if (args[0].equals("probe")) {
            try (InstanceLock lock = new InstanceLock(directory)) { System.out.println("ACQUIRED"); }
            catch (IOException expected) { System.out.println("BLOCKED"); }
            return;
        }
        HistoryStore store = new HistoryStore(directory, 40);
        if (args[0].equals("emit")) { store.save(SAMPLE); new AppSettings(40, false, "light", 0).save(directory); return; }
        if (args[0].equals("verify")) { check(store.load().equals(SAMPLE), "Cross-language history mismatch"); check(AppSettings.load(directory).sizeNoticeSeconds() == 0, "Cross-language notice setting"); return; }
        if (args[0].equals("emitPins")) { store.saveSnapshot(new HistoryStore.Snapshot(SAMPLE, SAMPLE.subList(0, 2))); return; }
        if (args[0].equals("verifyPins")) {
            var saved = store.loadSnapshot();
            check(saved.entries().equals(SAMPLE) && saved.pinned().equals(SAMPLE.subList(0, 2)), "Cross-language pins");
            return;
        }
        Files.createDirectories(directory);
        check(AppSettings.parse("{}").sizeNoticeSeconds() == 4, "Notice default");
        for (int seconds : new int[] {0, 2, 3, 4, 5}) {
            var configured = new AppSettings(40, false, "light", seconds);
            configured.save(directory);
            AppSettings.savePinned(directory, true);
            check(AppSettings.load(directory).sizeNoticeSeconds() == seconds, "Notice preference lost on pin");
        }
        for (int seconds : new int[] {-1, 1, 6})
            rejected(() -> AppSettings.parse("{\"sizeNoticeSeconds\":" + seconds + "}"));
        RejectedClipTracker tracker = new RejectedClipTracker();
        String tooLarge = "x".repeat(HistoryPolicy.MAX_TEXT_UNITS + 1);
        check(tracker.observe(tooLarge), "First oversize notice missing");
        check(!tracker.observe(new String(tooLarge)), "Repeated poll redisplayed notice");
        check(tracker.observe("y" + tooLarge.substring(1)), "Different same-size clip suppressed");
        tracker.observe("small");
        check(tracker.observe(tooLarge), "Later oversized copy suppressed");
        check(AppSettings.parse("{\"historyLimit\":500}").historyLimit() == 500, "Upper limit");
        for (String json : List.of("{\"historyLimit\":501}", "{\"historyLimit\":0}", "{\"historyLimit\":2147483648}",
                "{\"historyLimit\":1,\"historyLimit\":2}", "{\"pinned\":1}")) rejected(() -> AppSettings.parse(json));
        String maximum = "x".repeat(HistoryPolicy.MAX_TEXT_UNITS);
        check(!HistoryPolicy.accepts(maximum + "x"), "Oversized text accepted");
        check(HistoryPolicy.bounded(Collections.nCopies(500, maximum), 500).size() == 16, "Total text budget");
        check(HistoryPolicy.bounded(Collections.nCopies(501, "a"), 501).size() == 500, "Count budget");
        String preview = HistoryPolicy.preview("a".repeat(999) + "😀" + maximum);
        check(!preview.contains("\uD83D"), "Preview split a surrogate");
        var fivePins = List.of("pin1", "pin2", "pin3", "pin4", "pin5");
        var smallStore = new HistoryStore(directory, 1);
        smallStore.saveSnapshot(new HistoryStore.Snapshot(List.of("new", "old", "pin1"), fivePins));
        var restored = new HistoryStore(directory, 1).loadSnapshot();
        check(restored.pinned().equals(fivePins) && restored.entries().size() == 6
                && restored.entries().get(5).equals("new"), "Pins lost on restart/limit reduction");
        String prior = Files.readString(directory.resolve("history.properties"));
        rejected(() -> smallStore.saveSnapshot(new HistoryStore.Snapshot(List.of(), List.of("1", "2", "3", "4", "5", "6"))));
        check(prior.equals(Files.readString(directory.resolve("history.properties"))), "Sixth pin changed saved data");
        rejected(() -> smallStore.saveSnapshot(new HistoryStore.Snapshot(List.of(), List.of("same", "same"))));
        var longPins = new ArrayList<String>();
        for (int i = 0; i < 5; i++) longPins.add(i + maximum.substring(1));
        var budgeted = HistoryPolicy.withPins(Collections.nCopies(500, maximum), longPins, 500);
        check(budgeted.subList(0, 5).equals(longPins)
                && budgeted.stream().mapToInt(String::length).sum() <= HistoryPolicy.MAX_TOTAL_UNITS, "Pin memory budget");
        Files.writeString(directory.resolve("history.properties"), "version=2\npinned.count=6\ncount=0\n");
        rejected(store::loadSnapshot);
        Files.writeString(directory.resolve("history.properties"), "version=1\ncount=1\nentry.0=legacy\n");
        check(store.loadSnapshot().entries().equals(List.of("legacy")) && store.loadSnapshot().pinned().isEmpty(), "Legacy history migration");
        for (int i = 0; i < 100; i++) { store.save(SAMPLE); check(store.load().equals(SAMPLE), "History round trip"); }
        check(Files.getPosixFilePermissions(directory.resolve("history.properties"))
                .equals(PosixFilePermissions.fromString("rw-------")), "History permissions");
        try (var files = Files.list(directory)) { check(files.noneMatch(p -> p.toString().endsWith(".tmp")), "Temporary file leak"); }
        Files.writeString(directory.resolve("history.properties"), "version=1\ncount=1\nentry.0=\\uZZZZ\n");
        rejected(store::load);
        String oversized = "version=1\ncount=1\nentry.0=" + maximum + "x\n";
        Files.writeString(directory.resolve("history.properties"), oversized);
        rejected(store::load);
        check(Files.readString(directory.resolve("history.properties")).equals(oversized), "Oversized saved history changed");
        try (var file = java.nio.channels.FileChannel.open(directory.resolve("history.properties"), StandardOpenOption.WRITE)) {
            file.position(HistoryPolicy.MAX_FILE_BYTES);
            file.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
        }
        rejected(store::load);
        Files.writeString(directory.resolve("settings.json"), " ".repeat(65_537));
        rejected(() -> AppSettings.load(directory));
        Path linked = directory.resolve("linked");
        Files.createSymbolicLink(linked, directory.resolve("settings.json"));
        rejected(() -> HistoryPolicy.read(linked, 65_536));
        Files.delete(linked);
        WindowSizeStore sizes = new WindowSizeStore(directory);
        var custom = WindowSizeStore.factory().withCurrent(900, 500).saveCurrentAsDefault();
        sizes.save(custom.withCurrent(600, 300));
        check(sizes.load().resetToDefault().equals(custom), "Size default round trip");
        testProcesses();
        System.out.println("PASS: Java storage, budgets, size, permissions, malformed input, child-process cleanup");
    }

    static void testProcesses() throws Exception {
        var bean = (com.sun.management.UnixOperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        try (CommandRunner runner = new CommandRunner()) {
            for (int i = 0; i < 10; i++) runner.run(1000, "/usr/bin/printf", "warmup");
            long before = bean.getOpenFileDescriptorCount();
            for (int i = 0; i < 200; i++) check(runner.run(1000, "/usr/bin/printf", "ok").equals("ok"), "Process output");
            rejected(() -> runner.run(100, "/usr/bin/yes")); // Flood output until timeout; must not deadlock.
            long after = bean.getOpenFileDescriptorCount();
            check(after <= before + 3, "File descriptor growth: " + before + " -> " + after);
            System.out.println("Descriptors after warmup/200 commands: " + before + "/" + after);
        }
        for (int i = 0; i < 20; i++) {
            CommandRunner runner = new CommandRunner();
            Thread worker = new Thread(() -> {
                try { runner.run(5000, "/bin/sleep", "5"); }
                catch (IOException | InterruptedException expected) { }
            });
            worker.start();
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (ProcessHandle.current().children().noneMatch(ProcessHandle::isAlive)
                    && System.nanoTime() < deadline) Thread.sleep(5);
            if (i % 2 == 0) worker.interrupt(); else runner.close();
            worker.join(2000);
            runner.close();
            check(!worker.isAlive(), "Cancelled command thread survived");
        }
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (ProcessHandle.current().children().anyMatch(ProcessHandle::isAlive)
                && System.nanoTime() < deadline) Thread.sleep(10);
        check(ProcessHandle.current().children().noneMatch(ProcessHandle::isAlive), "Child process survived cleanup");
        try (CommandRunner runner = new CommandRunner()) {
            runner.close();
            rejected(() -> runner.run(1000, "/usr/bin/printf", "must not run"));
        }
    }
}
