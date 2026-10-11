import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.util.function.Consumer;

import javax.swing.Timer;

/** Watches copied text. Swing's timer keeps the callback on the UI thread. */
final class ClipboardWatcher {
    private final Clipboard clipboard;
    private final Timer timer;
    private String lastObserved;
    private boolean paused;
    private final RejectedClipTracker rejected = new RejectedClipTracker();

    private final Consumer<String> onNewText;
    private final java.util.function.IntConsumer onOversized;
    private boolean running;
    private boolean seedNext;

    ClipboardWatcher(Consumer<String> onNewText, java.util.function.IntConsumer onOversized) {
        this(Toolkit.getDefaultToolkit().getSystemClipboard(), onNewText, onOversized);
    }

    ClipboardWatcher(Clipboard clipboard, Consumer<String> onNewText,
                     java.util.function.IntConsumer onOversized) {
        this.clipboard = clipboard;
        this.onNewText = onNewText;
        this.onOversized = onOversized;
        seed();
        timer = new Timer(1000, event -> poll());
    }

    private void seed() {
        try {
            String text = readText();
            lastObserved = HistoryPolicy.accepts(text) ? text : null;
            rejected.observe(text);
            seedNext = false;
        } catch (RuntimeException busy) { seedNext = true; }
    }

    void poll() {
        if (!running || paused) return;
        if (seedNext) { seed(); return; }
        final String text;
        try { text = readText(); }
        catch (RuntimeException busy) { return; } // Keep the previous observation on a transient failure.
        if (rejected.observe(text)) onOversized.accept(text.length());
        if (text != null && text.equals(lastObserved)) return;
        lastObserved = HistoryPolicy.accepts(text) ? text : null;
        if (HistoryPolicy.accepts(text)) onNewText.accept(text);
    }

    boolean isPaused() { return paused; }

    void setPaused(boolean value) {
        paused = value;
        lastObserved = null;
        rejected.clear();
        if (!value) seed();
    }

    void start() {
        if (running) return;
        running = true;
        timer.start();
    }

    void stop() {
        running = false;
        timer.stop();
        lastObserved = null;
        rejected.clear();
    }

    void copy(String text) {
        clipboard.setContents(new StringSelection(text), null);
        lastObserved = text;
        seedNext = false;
        rejected.clear();
    }

    private String readText() {
        try {
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                return (String) clipboard.getData(DataFlavor.stringFlavor);
            }
        } catch (UnsupportedFlavorException | IOException | IllegalStateException error) {
            throw new IllegalStateException("Clipboard temporarily unavailable", error);
        }
        return null;
    }
}
