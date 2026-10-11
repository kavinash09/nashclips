package nashclip;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Owns child processes; output, wait time, and lifetime are bounded. */
final class CommandRunner implements AutoCloseable {
    private final Set<Process> children = new HashSet<>();
    private boolean closed;

    String run(long timeoutMillis, String... command) throws IOException, InterruptedException {
        final Process child;
        synchronized (this) {
            if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Service stopped");
            child = new ProcessBuilder(command).redirectErrorStream(true).start();
            children.add(child);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (var input = child.getInputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    // Drain excess output too, so a full pipe cannot deadlock the child.
                    synchronized (output) {
                        output.write(buffer, 0, Math.min(count, 4096 - output.size()));
                    }
                }
            } catch (IOException ignored) { /* Shutdown closes the pipe. */ }
        }, "nashclip-command-output");
        reader.setDaemon(true);
        try {
            child.getOutputStream().close();
            reader.start();
            if (!child.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) throw new IOException("Command timed out");
            reader.join(500);
            if (reader.isAlive()) throw new IOException("Command output did not close");
            if (child.exitValue() != 0) throw new IOException("Command failed (" + child.exitValue() + ")");
            synchronized (output) { return output.toString(StandardCharsets.UTF_8).trim(); }
        } finally {
            child.destroyForcibly();
            try { child.getInputStream().close(); } catch (IOException ignored) { }
            try { child.getErrorStream().close(); } catch (IOException ignored) { }
            try { child.getOutputStream().close(); } catch (IOException ignored) { }
            synchronized (this) { children.remove(child); }
        }
    }

    @Override public synchronized void close() {
        closed = true;
        for (Process child : children) child.destroyForcibly();
    }
}
