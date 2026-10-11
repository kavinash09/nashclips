import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.function.IntFunction;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/** Lays out variable-height tiles in as many columns as fit. */
final class HistoryGrid extends JPanel {
    private static final int TILE_WIDTH = 220;
    private static final int GAP = 12;
    private static final int HORIZONTAL_GAP = 8;

    private final DefaultListModel<String> history;
    private final IntFunction<JPanel> tileAt;
    private int columnsCount = 3;

    HistoryGrid(DefaultListModel<String> history, IntFunction<JPanel> tileAt) {
        super(new BorderLayout());
        setOpaque(false);
        this.history = history;
        this.tileAt = tileAt;
        refresh();
    }

    void setAvailableWidth(int windowWidth) {
        // Reserve the root padding, resize grips, scrollbar, and FlowLayout's outer gaps.
        int available = windowWidth - 28 - 14 - 18 - 2 * HORIZONTAL_GAP;
        int count = Math.max(1, Math.min(3,
                (available + HORIZONTAL_GAP) / (TILE_WIDTH + HORIZONTAL_GAP)));
        if (columnsCount != count) {
            columnsCount = count;
            refresh();
        }
    }

    void refresh() {
        removeAll();
        if (history.isEmpty()) {
            JPanel empty = ClipboardTheme.transparentPanel(new BorderLayout(0, 12));
            empty.setBorder(new EmptyBorder(32, 12, 32, 12));
            JLabel title = ClipboardTheme.label("A little space for your next idea", Font.BOLD,
                    17, ClipboardTheme.TEXT);
            title.setHorizontalAlignment(JLabel.CENTER);
            JLabel hint = ClipboardTheme.label("Copy some text. It will appear here, ready to use again.",
                    Font.PLAIN, 12, ClipboardTheme.MUTED);
            hint.setHorizontalAlignment(JLabel.CENTER);
            empty.add(title, BorderLayout.CENTER);
            empty.add(hint, BorderLayout.SOUTH);
            add(empty, BorderLayout.CENTER);
        } else {
            JPanel columns = ClipboardTheme.transparentPanel(
                    new FlowLayout(FlowLayout.CENTER, HORIZONTAL_GAP, 0));
            JPanel[] stacks = new JPanel[columnsCount];
            int[] heights = new int[columnsCount];
            for (int column = 0; column < columnsCount; column++) {
                JPanel stack = ClipboardTheme.transparentPanel(null);
                stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
                stacks[column] = stack;
                columns.add(stack);
            }
            for (int index = 0; index < history.size(); index++) {
                JPanel tile = tileAt.apply(index);
                int shortest = 0;
                for (int column = 1; column < columnsCount; column++) {
                    if (heights[column] < heights[shortest]) shortest = column;
                }
                if (stacks[shortest].getComponentCount() > 0) {
                    stacks[shortest].add(Box.createVerticalStrut(GAP));
                    heights[shortest] += GAP;
                }
                stacks[shortest].add(tile);
                heights[shortest] += tile.getPreferredSize().height;
            }
            add(columns, BorderLayout.CENTER);
        }
        revalidate();
        repaint();
    }
}
