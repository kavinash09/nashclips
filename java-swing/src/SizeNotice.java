import java.awt.*;
import java.awt.event.HierarchyEvent;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/** A click-through glass pane with one in-memory, dismissible size notice. */
final class SizeNotice extends JPanel implements AutoCloseable {
    private final JPanel card = new JPanel(new BorderLayout(12, 0));
    private final JLabel detail = new JLabel();
    private final Timer animation;
    private int seconds;
    private float alpha;
    private long started;
    private boolean closed;

    SizeNotice() {
        super(null);
        setOpaque(false);
        setVisible(false);
        card.setBackground(new Color(112, 34, 37));
        card.setBorder(new EmptyBorder(12, 14, 12, 12));
        JLabel icon = new JLabel("!");
        icon.setForeground(new Color(255, 180, 180));
        icon.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
        card.add(icon, BorderLayout.WEST);
        JPanel words = new JPanel(new BorderLayout(0, 4));
        words.setOpaque(false);
        JLabel title = new JLabel("Last copied clip was too large");
        title.setForeground(Color.WHITE);
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        detail.setForeground(new Color(255, 225, 225));
        detail.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        words.add(title, BorderLayout.NORTH);
        words.add(detail, BorderLayout.CENTER);
        card.add(words, BorderLayout.CENTER);
        JButton dismiss = new JButton("×");
        dismiss.setForeground(Color.WHITE);
        dismiss.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        dismiss.setBorder(new EmptyBorder(4, 8, 4, 8));
        dismiss.setContentAreaFilled(false);
        dismiss.setFocusable(false);
        dismiss.setToolTipText("Dismiss size notice");
        dismiss.getAccessibleContext().setAccessibleName("Dismiss size notice");
        dismiss.addActionListener(event -> dismiss());
        card.add(dismiss, BorderLayout.EAST);
        add(card);
        animation = new Timer(16, event -> tick());
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (isShowing()) begin(); else animation.stop();
            }
        });
    }

    void showSize(int units, int seconds) {
        if (closed) return;
        this.seconds = seconds;
        detail.setText(String.format(java.util.Locale.ROOT,
                "%.1f KiB text · 128 KiB limit. Not saved.", units / 512.0));
        setVisible(true);
        if (isShowing()) begin();
    }

    private void begin() {
        if (closed) return;
        started = System.nanoTime();
        alpha = 0;
        animation.restart();
    }

    private void tick() {
        double elapsed = (System.nanoTime() - started) / 1_000_000_000.0;
        if (seconds > 0 && elapsed >= seconds) { dismiss(); return; }
        alpha = (float) Math.min(1, elapsed / 0.15);
        if (seconds > 0) alpha = Math.min(alpha, (float) Math.max(0, (seconds - elapsed) / 0.3));
        if (seconds == 0 && alpha == 1) animation.stop();
        repaint();
    }

    private void dismiss() { animation.stop(); setVisible(false); detail.setText(""); }

    @Override public void doLayout() {
        int width = Math.min(440, Math.max(0, getWidth() - 32));
        card.setBounds((getWidth() - width) / 2, 62, width, 72);
    }

    @Override public boolean contains(int x, int y) {
        return isVisible() && card.getBounds().contains(x, y);
    }

    @Override public void paint(Graphics graphics) {
        Graphics2D copy = (Graphics2D) graphics.create();
        copy.setComposite(AlphaComposite.SrcOver.derive(alpha));
        super.paint(copy);
        copy.dispose();
    }

    @Override public void close() { closed = true; dismiss(); }
}
