import java.awt.datatransfer.*;
import java.util.*;
import javax.swing.SwingUtilities;

/** Private in-process clipboard. No access to the user's system clipboard. */
public class ClipboardWatcherChecks {
    static final class FlakyClipboard extends Clipboard {
        boolean busy;
        boolean failWrite;
        FlakyClipboard() { super("nashclip-test"); }
        @Override public synchronized boolean isDataFlavorAvailable(DataFlavor flavor) {
            if (busy) throw new IllegalStateException("Simulated busy owner");
            return super.isDataFlavorAvailable(flavor);
        }
        @Override public synchronized void setContents(Transferable value, ClipboardOwner owner) {
            if (failWrite) throw new IllegalStateException("Simulated write failure");
            super.setContents(value, owner);
        }
    }
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var clipboard = new FlakyClipboard();
            clipboard.setContents(new StringSelection("startup baseline"), null);
            var captured = new ArrayList<String>();
            var rejected = new ArrayList<Integer>();
            var watcher = new ClipboardWatcher(clipboard, captured::add, rejected::add);
            try {
                watcher.start(); watcher.start();
                watcher.poll();
                if (!captured.isEmpty()) throw new AssertionError("Startup content captured");
                clipboard.setContents(new StringSelection("first"), null); watcher.poll();
                clipboard.busy = true; watcher.poll();
                clipboard.busy = false; watcher.poll();
                if (!captured.equals(List.of("first"))) throw new AssertionError("Busy read duplicated history");
                String large = "x".repeat(HistoryPolicy.MAX_TEXT_UNITS + 1);
                clipboard.setContents(new StringSelection(large), null); watcher.poll();
                clipboard.busy = true; watcher.poll(); clipboard.busy = false; watcher.poll();
                if (rejected.size() != 1) throw new AssertionError("Busy read redisplayed size notice");
                watcher.setPaused(true);
                clipboard.setContents(new StringSelection("private"), null); watcher.poll();
                clipboard.busy = true; watcher.setPaused(false); watcher.poll();
                clipboard.busy = false; watcher.poll(); watcher.poll();
                if (captured.size() != 1) throw new AssertionError("Resume captured paused text after busy read");
                clipboard.setContents(new StringSelection("second"), null); watcher.poll();
                watcher.copy("own copy"); watcher.poll();
                if (!captured.equals(List.of("first", "second"))) throw new AssertionError("Copy suppression failed");
                clipboard.setContents(new StringSelection(" \n\t"), null); watcher.poll();
                if (captured.size() != 2) throw new AssertionError("Blank clipboard text captured");
                clipboard.failWrite = true;
                try { watcher.copy("must fail"); throw new AssertionError("Clipboard write failure hidden"); }
                catch (IllegalStateException expected) { }
                clipboard.failWrite = false;
                watcher.stop();
                clipboard.setContents(new StringSelection("stopped"), null); watcher.poll();
                if (captured.size() != 2) throw new AssertionError("Callback after stop");
            } finally { watcher.stop(); }
        });
        System.out.println("PASS: private Swing clipboard busy reads, pause/resume, oversize dedup, own-copy suppression, stop");
    }
}
