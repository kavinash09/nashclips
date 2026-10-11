import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Launches a disposable protocol fixture, never the macOS global-hotkey helper. */
public class HotkeyProtocolTests {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); Files.createDirectories(root);
        TestSupport.run("hotkey.protocol.ready.press.click.errors.debounce", () -> {
            Path helper = root.resolve("fixture.sh");
            Files.writeString(helper, "#!/bin/sh\nprintf '%s\\n' READY HOTKEY HOTKEY 'CLICK -25 80' 'CLICK bad 1' MOUSE_UNAVAILABLE 'ERROR conflict'\n");
            Files.setPosixFilePermissions(helper, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            var ready = new AtomicInteger(); var pressed = new AtomicInteger(); var mouse = new AtomicInteger();
            var clicks = new CopyOnWriteArrayList<java.awt.Point>(); var errors = new CopyOnWriteArrayList<String>();
            var done = new CountDownLatch(1);
            try (var hotkey = new GlobalHotkey(helper)) {
                hotkey.start(pressed::incrementAndGet, clicks::add, ready::incrementAndGet, e -> { errors.add(e); done.countDown(); }, mouse::incrementAndGet);
                hotkey.start(pressed::incrementAndGet, clicks::add, ready::incrementAndGet, errors::add, mouse::incrementAndGet);
                TestSupport.check(done.await(5, TimeUnit.SECONDS), "Helper callbacks timed out");
                TestSupport.check(ready.get() == 1 && pressed.get() == 1 && mouse.get() == 1, "Duplicate/missing callback");
                TestSupport.check(clicks.equals(List.of(new java.awt.Point(-25,80))) && errors.equals(List.of("conflict")), "Malformed event parsing");
            }
        });
        TestSupport.run("hotkey.missing-helper", () -> {
            var error = new CountDownLatch(1);
            try (var hotkey = new GlobalHotkey(root.resolve("absent"))) {
                hotkey.start(() -> {}, p -> {}, () -> {}, e -> error.countDown(), () -> {});
                TestSupport.check(error.await(5, TimeUnit.SECONDS), "Missing helper not reported");
            }
        });
        TestSupport.run("hotkey.close-terminates-helper", () -> {
            Path helper = root.resolve("waiting.sh");
            Files.writeString(helper, "#!/bin/sh\nprintf '%s\\n' READY\nexec /bin/sleep 60\n");
            Files.setPosixFilePermissions(helper, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            var ready = new CountDownLatch(1); var hotkey = new GlobalHotkey(helper);
            try {
                hotkey.start(() -> {}, p -> {}, ready::countDown, e -> {}, () -> {});
                TestSupport.check(ready.await(5, TimeUnit.SECONDS), "No ready event");
                Process child = (Process) TestSupport.get(hotkey, "process");
                hotkey.close(); hotkey.close();
                TestSupport.check(child.waitFor(5, TimeUnit.SECONDS), "Helper survived close");
            } finally { hotkey.close(); }
        });
        TestSupport.finish();
    }
}
