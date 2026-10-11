import java.awt.Point;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Owns the macOS shortcut helper. Callbacks run on a background thread. */
final class GlobalHotkey implements AutoCloseable {
    static final String DISPLAY = "⌃⌥V";
    private volatile Process process;
    private volatile boolean closed;
    private boolean started;
    private final Path executable;
    GlobalHotkey() { this(Path.of("native", "nashclip-hotkey").toAbsolutePath()); }
    GlobalHotkey(Path executable) { this.executable = executable; }

    synchronized void start(Runnable onPressed, Consumer<Point> onClick, Runnable onReady,
            Consumer<String> onError, Runnable onMouseUnavailable) {
        if (started || closed) {
            return;
        }
        started = true;
        Thread listener = new Thread(() -> listen(onPressed, onClick, onReady,
                onError, onMouseUnavailable), "nashclip-hotkey");
        listener.setDaemon(true);
        listener.start();
    }

    private void listen(Runnable onPressed, Consumer<Point> onClick, Runnable onReady,
            Consumer<String> onError, Runnable onMouseUnavailable) {
        try {
            if (!Files.isExecutable(executable)) {
                throw new IOException("Run sh native/build.sh from the project folder and restart.");
            }
            synchronized (this) {
                if (closed) {
                    return;
                }
                process = new ProcessBuilder(executable.toString()).redirectErrorStream(true).start();
            }
            boolean reportedError = false;
            long lastPress = 0;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!closed && (line = reader.readLine()) != null) {
                    if (line.equals("READY")) {
                        onReady.run();
                    } else if (line.equals("HOTKEY")) {
                        long now = System.nanoTime();
                        // Avoid flooding the UI if the shortcut is held down.
                        if (now - lastPress > 300_000_000L) {
                            lastPress = now;
                            onPressed.run();
                        }
                    } else if (line.startsWith("CLICK ")) {
                        String[] coordinates = line.split(" ");
                        if (coordinates.length == 3) {
                            try {
                                onClick.accept(new Point(Integer.parseInt(coordinates[1]),
                                        Integer.parseInt(coordinates[2])));
                            } catch (NumberFormatException ignored) {
                                // Ignore an incomplete or malformed native event line.
                            }
                        }
                    } else if (line.equals("MOUSE_UNAVAILABLE")) {
                        onMouseUnavailable.run();
                    } else if (line.startsWith("ERROR ")) {
                        reportedError = true;
                        onError.accept(line.substring(6));
                    }
                }
            }
            if (!closed && !reportedError) {
                onError.accept("Shortcut listener stopped. Restart nashclip to reconnect.");
            }
        } catch (IOException | RuntimeException error) {
            if (!closed) {
                onError.accept(error.getMessage());
            }
        } finally {
            Process helper = process;
            if (helper != null) {
                helper.destroyForcibly();
                try { helper.getOutputStream().close(); } catch (IOException ignored) { }
                try { helper.getInputStream().close(); } catch (IOException ignored) { }
                try { helper.getErrorStream().close(); } catch (IOException ignored) { }
                synchronized (this) { if (process == helper) process = null; }
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (process != null) {
            process.destroyForcibly();
        }
    }
}
