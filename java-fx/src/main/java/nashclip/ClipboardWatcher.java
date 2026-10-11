package nashclip;

import java.util.function.Consumer;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.util.Duration;

/** Reports new system clipboard text on the JavaFX application thread. */
final class ClipboardWatcher {
    interface TextClipboard {
        String read();
        void write(String text);
    }
    private final TextClipboard clipboard;
    private final Timeline timer;
    private String lastObserved;
    private boolean paused;
    private final RejectedClipTracker rejected = new RejectedClipTracker();

    private final Consumer<String> onNewText;
    private final java.util.function.IntConsumer onOversized;
    private boolean running;
    private boolean seedNext;

    ClipboardWatcher(Consumer<String> onNewText, java.util.function.IntConsumer onOversized) {
        this(Clipboard.getSystemClipboard(), onNewText, onOversized);
    }

    ClipboardWatcher(Clipboard clipboard, Consumer<String> onNewText,
                     java.util.function.IntConsumer onOversized) {
        this(new TextClipboard() {
            public String read() {
                if (!clipboard.hasString()) return null;
                String value = clipboard.getString();
                if (value == null) throw new IllegalStateException("Clipboard owner is not ready");
                return value;
            }
            public void write(String text) {
                ClipboardContent content = new ClipboardContent();
                content.putString(text);
                if (!clipboard.setContent(content)) throw new IllegalStateException("Clipboard is unavailable");
            }
        }, onNewText, onOversized);
    }

    ClipboardWatcher(TextClipboard clipboard, Consumer<String> onNewText,
                     java.util.function.IntConsumer onOversized) {
        this.clipboard = clipboard;
        this.onNewText = onNewText;
        this.onOversized = onOversized;
        seed();
        timer = new Timeline(new KeyFrame(Duration.seconds(1), event -> poll()));
        timer.setCycleCount(Timeline.INDEFINITE);
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
        timer.play();
    }

    void stop() {
        running = false;
        timer.stop();
        lastObserved = null;
        rejected.clear();
    }

    void copy(String text) {
        clipboard.write(text);
        lastObserved = text;
        seedNext = false;
        rejected.clear();
    }
    private String readText() { return clipboard.read(); }
}
