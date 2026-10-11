import java.awt.AWTException;
import java.awt.Robot;
import java.awt.event.KeyEvent;

/** Remembers the destination app, activates it, and sends Cmd+V. */
final class PasteService implements AutoCloseable {
    record Target(long windowToken) { }
    record Result(boolean sent, String message) { }

    private Thread focusMonitor;
    private volatile boolean closed;
    /** Platform effects are injectable so tests never activate apps or send real keys. */
    interface PlatformAccess extends AutoCloseable {
        void remember();
        long freeze();
        boolean restore(long token);
        boolean isOriginalWindow(long token);
        void release(long token);
        boolean clipboardMatches(String text);
        void awaitRestoration() throws InterruptedException;
        void sendPaste() throws AWTException;
        void close();
    }
    private final PlatformAccess platform;
    PasteService() { this(new MacPlatform()); }
    PasteService(PlatformAccess platform) { this.platform = java.util.Objects.requireNonNull(platform); }

    synchronized void start() {
        if (closed || focusMonitor != null) return;
        focusMonitor = new Thread(() -> {
            while (!closed && !Thread.currentThread().isInterrupted()) {
                rememberFrontmostApplication();
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "nashclip-focus-monitor");
        focusMonitor.setDaemon(true);
        focusMonitor.start();
    }

    void rememberFrontmostApplication() { if (!closed) platform.remember(); }

    Target target() { return new Target(platform.freeze()); }
    void cancel(Target target) { platform.release(target.windowToken()); }

    Result paste(Target target, String expectedText) {
        try {
            if (closed || Thread.currentThread().isInterrupted()) return new Result(false, "Paste cancelled.");
            if (!platform.clipboardMatches(expectedText)) return new Result(false, "Paste cancelled because the clipboard changed.");
            if (target.windowToken() == 0 || !platform.restore(target.windowToken()))
                return new Result(false, "Copied. Original window could not be verified; paste with Command+V.");
            platform.awaitRestoration();
            synchronized (this) {
                if (closed || Thread.currentThread().isInterrupted()) return new Result(false, "Paste cancelled.");
                if (!platform.isOriginalWindow(target.windowToken()))
                    return new Result(false, "Copied, but the original window did not regain focus.");
                if (!platform.clipboardMatches(expectedText)) return new Result(false, "Paste cancelled because the clipboard changed.");
                platform.sendPaste();
            }
            return new Result(true, "Cmd+V sent to the original window.");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new Result(false, "Paste cancelled.");
        } catch (AWTException | SecurityException error) {
            return new Result(false, "Copied, but automatic paste failed: " + error.getMessage());
        } finally {
            platform.release(target.windowToken());
        }
    }

    private static final class MacPlatform implements PlatformAccess {
        private final NativeFocus focus = new NativeFocus();
        public void remember() { focus.remember(); }
        public long freeze() { return focus.freeze(); }
        public boolean restore(long token) { return focus.restore(token); }
        public boolean isOriginalWindow(long token) { return focus.isOriginalWindow(token); }
        public void release(long token) { focus.release(token); }
        public void close() { focus.close(); }
        public void awaitRestoration() throws InterruptedException { Thread.sleep(350); }
        public boolean clipboardMatches(String expectedText) {
            try {
                Object value = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                        .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
                return expectedText.equals(value);
            } catch (Exception error) { return false; }
        }
        public void sendPaste() throws AWTException {
            Robot robot = new Robot();
            try { robot.keyPress(KeyEvent.VK_META); robot.keyPress(KeyEvent.VK_V); }
            finally {
                try { robot.keyRelease(KeyEvent.VK_V); }
                finally { robot.keyRelease(KeyEvent.VK_META); }
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        platform.close();
        if (focusMonitor != null) focusMonitor.interrupt();
    }
}
