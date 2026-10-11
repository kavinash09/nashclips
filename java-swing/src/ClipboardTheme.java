import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JToggleButton;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;

/** Small, reusable Swing components for nashclip's desktop appearance. */
final class ClipboardTheme {
    static Color BACKGROUND;
    static Color SURFACE;
    static Color BORDER;
    static Color TEXT;
    static Color MUTED;
    static Color ACCENT;
    static Color ACCENT_SURFACE;
    static Color GREEN;
    static Color DELETE_HOVER;
    static Color DELETE_TEXT;
    static Color USE_HOVER;
    static Color USE_PRESSED;
    static Color SCROLL_THUMB;
    static Color PIN_GLOW;
    static Color PIN_ACTIVE;

    static { use("light"); }

    static void use(String theme) {
        boolean dark = "dark".equals(theme);
        BACKGROUND = dark ? new Color(30, 28, 26) : new Color(249, 243, 225);
        SURFACE = dark ? new Color(43, 40, 35) : new Color(255, 252, 241);
        BORDER = dark ? new Color(87, 75, 53) : new Color(226, 211, 171);
        TEXT = dark ? new Color(248, 238, 213) : new Color(58, 47, 29);
        MUTED = dark ? new Color(190, 174, 141) : new Color(119, 102, 70);
        ACCENT = dark ? new Color(255, 218, 128) : new Color(117, 80, 13);
        ACCENT_SURFACE = dark ? new Color(91, 68, 36) : new Color(247, 216, 111);
        GREEN = dark ? new Color(155, 211, 144) : new Color(91, 111, 53);
        DELETE_HOVER = dark ? new Color(94, 57, 48) : new Color(250, 225, 202);
        DELETE_TEXT = dark ? new Color(255, 161, 132) : new Color(158, 64, 35);
        USE_HOVER = dark ? new Color(115, 84, 42) : new Color(242, 203, 80);
        USE_PRESSED = dark ? new Color(129, 92, 43) : new Color(229, 186, 63);
        SCROLL_THUMB = dark ? new Color(110, 93, 63) : new Color(190, 170, 121);
        PIN_GLOW = dark ? new Color(155, 111, 47) : new Color(247, 216, 111);
        PIN_ACTIVE = dark ? new Color(221, 177, 88) : new Color(91, 53, 9);
    }

    private ClipboardTheme() { }

    static Font font(int style, int size) {
        return new Font("SansSerif", style, size);
    }

    static JLabel label(String text, int style, int size, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(font(style, size));
        label.setForeground(color);
        return label;
    }

    static JPanel transparentPanel(java.awt.LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    static final class Card extends JPanel {
        Card(java.awt.LayoutManager layout) {
            super(layout);
            setOpaque(false);
            setBorder(new EmptyBorder(10, 12, 10, 12));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = drawing(graphics);
            g.setColor(SURFACE);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 22, 22);
            g.setColor(BORDER);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 22, 22);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    static final class ActionButton extends JButton {
        private final boolean destructive;

        ActionButton(String text, boolean destructive) {
            super(text);
            this.destructive = destructive;
            setFont(font(Font.BOLD, 12));
            setForeground(destructive ? MUTED : ACCENT);
            setBorder(new EmptyBorder(6, 12, 6, 12));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setFocusable(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            if (destructive) {
                setPreferredSize(new Dimension(30, 30));
            }
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = drawing(graphics);
            boolean hovered = getModel().isRollover();
            boolean pressed = getModel().isPressed();
            if (destructive) {
                if (hovered || pressed) {
                    g.setColor(DELETE_HOVER);
                    g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                }
                g.setColor(hovered ? DELETE_TEXT : MUTED);
                g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                int x = getWidth() / 2;
                int y = getHeight() / 2;
                g.drawRoundRect(x - 5, y - 3, 10, 10, 2, 2);
                g.drawLine(x - 7, y - 6, x + 7, y - 6);
                g.drawLine(x - 2, y - 9, x + 2, y - 9);
                g.drawLine(x - 2, y, x - 2, y + 4);
                g.drawLine(x + 2, y, x + 2, y + 4);
                g.dispose();
                return;
            }
            g.setColor(pressed ? USE_PRESSED : hovered ? USE_HOVER : ACCENT_SURFACE);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    static final class PinButton extends JToggleButton {
        private float glow;
        private final Timer glowAnimation;

        PinButton() {
            setPreferredSize(new Dimension(30, 30));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setFocusable(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            glowAnimation = new Timer(16, event -> {
                float target = targetGlow();
                glow += (target - glow) * 0.28f;
                if (Math.abs(target - glow) < 0.02f) {
                    glow = target;
                    ((Timer) event.getSource()).stop();
                }
                repaint();
            });
            addChangeListener(event -> {
                if (isShowing()) {
                    glowAnimation.start();
                } else {
                    glow = targetGlow();
                    repaint();
                }
            });
        }

        @Override public void removeNotify() {
            glowAnimation.stop();
            super.removeNotify();
        }

        private float targetGlow() {
            return isSelected() ? 0.85f : getModel().isRollover() ? 0.38f : 0f;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = drawing(graphics);
            if (glow > 0f) {
                g.setColor(new Color(PIN_GLOW.getRed(), PIN_GLOW.getGreen(),
                        PIN_GLOW.getBlue(), Math.round(170 * glow)));
                g.fillOval(2, 2, getWidth() - 4, getHeight() - 4);
            }
            g.setColor(isSelected() ? PIN_ACTIVE : MUTED);
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            int x = getWidth() / 2;
            int y = getHeight() / 2;
            g.drawRoundRect(x - 4, y - 9, 8, 7, 5, 5);
            g.drawLine(x - 6, y - 2, x + 6, y - 2);
            g.drawLine(x - 4, y - 2, x - 3, y + 3);
            g.drawLine(x + 4, y - 2, x + 3, y + 3);
            g.drawLine(x - 3, y + 3, x + 3, y + 3);
            g.drawLine(x, y + 3, x, y + 10);
            g.dispose();
        }
    }

    static final class ClipboardMark extends JComponent {
        private final int size;

        ClipboardMark(int size) {
            this.size = size;
            setPreferredSize(new Dimension(size, size));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = drawing(graphics);
            g.setColor(ACCENT_SURFACE);
            g.fillRoundRect(0, 0, size, size, 14, 14);
            g.scale(size / 44.0, size / 44.0);
            g.setColor(ACCENT);
            g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            g.drawRoundRect(13, 12, 19, 23, 4, 4);
            g.setColor(ACCENT_SURFACE);
            g.fillRoundRect(18, 9, 9, 6, 3, 3);
            g.setColor(ACCENT);
            g.drawRoundRect(18, 9, 9, 6, 3, 3);
            g.drawLine(18, 22, 27, 22);
            g.drawLine(18, 27, 24, 27);
            g.dispose();
        }
    }

    static void styleScrollBar(JScrollBar bar) {
        bar.setPreferredSize(new Dimension(9, 0));
        bar.setUnitIncrement(24);
        bar.setUI(new BasicScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {
                thumbColor = SCROLL_THUMB;
                trackColor = BACKGROUND;
            }

            @Override
            protected JButton createDecreaseButton(int orientation) {
                return noArrow();
            }

            @Override
            protected JButton createIncreaseButton(int orientation) {
                return noArrow();
            }

            private JButton noArrow() {
                JButton button = new JButton();
                button.setPreferredSize(new Dimension(0, 0));
                return button;
            }

            @Override
            protected void paintThumb(Graphics graphics, JComponent component,
                    java.awt.Rectangle bounds) {
                Graphics2D g = drawing(graphics);
                g.setColor(isThumbRollover() ? MUTED : thumbColor);
                g.fillRoundRect(bounds.x + 2, bounds.y, Math.max(3, bounds.width - 4),
                        bounds.height, 5, 5);
                g.dispose();
            }
        });
    }

    private static Graphics2D drawing(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        return g;
    }
}
