import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.BiConsumer;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** A non-focusable edge drawer. All methods run on Swing's event thread. */
final class EdgeDrawer implements AutoCloseable {
    enum ResizeEdge { LEFT, RIGHT, TOP, BOTTOM }
    private static final int HANDLE_WIDTH = 18;
    private static final int HANDLE_HEIGHT = 96;
    private static final float IDLE_OPACITY = 0.66f;
    private static final long FULL_SLIDE_NANOS = 340_000_000L;
    private static final long CLOSE_SLIDE_NANOS = 150_000_000L;
    private static final long MIN_SLIDE_NANOS = 120_000_000L;
    private static final int HOVER_DELAY_MS = 180;
    private static final int DRAG_THRESHOLD = 5;
    private static final long SNAP_NANOS = 220_000_000L;

    private final JFrame frame;
    private final JWindow handle = new JWindow();
    private final boolean translucentHandle;
    private final Timer hoverDelay;
    private final Timer animation;
    private final Timer snapAnimation;
    private final Timer pointerWatcher;
    private final JButton arrow;
    private Rectangle screen;
    private int panelWidth;
    private int panelHeight;
    private int desiredWidth;
    private int desiredHeight;
    private int panelY;
    private int handleY = Integer.MIN_VALUE;
    private int visibleWidth;
    private int animationFrom;
    private int animationTo;
    private long animationStarted;
    private long animationDuration;
    private long snapStarted;
    private int snapFromX;
    private int snapToX;
    private int snapFromY;
    private int snapToY;
    private int snapFrameFromX;
    private int snapFrameToX;
    private int snapFrameFromY;
    private int snapFrameToY;
    private boolean open;
    private boolean closed;
    private boolean rightSide = true;
    private boolean pinned;
    private boolean clickedInside;
    private boolean suppressHover;
    private boolean handleHovered;
    private boolean modalInteraction;
    private boolean dragging;
    private boolean resizing;
    private ResizeEdge resizeEdge;
    private Point resizePress;
    private int resizeWidthAtPress;
    private int resizeHeightAtPress;
    private int resizeTopAtPress;
    private BiConsumer<Integer, Integer> onResized;
    private boolean dragFromHeader;
    private boolean dragOpenDrawer;
    private boolean snapOpenDrawer;
    private boolean skipClick;
    private Point dragPress;
    private Point handleAtPress;
    private Point frameAtPress;
    private Point lastArrowPress;
    private long lastArrowPressAt;

    EdgeDrawer(JFrame frame) {
        this(frame, false, WindowSizeStore.FACTORY_WIDTH, WindowSizeStore.FACTORY_HEIGHT);
    }

    EdgeDrawer(JFrame frame, boolean pinned) {
        this(frame, pinned, WindowSizeStore.FACTORY_WIDTH, WindowSizeStore.FACTORY_HEIGHT);
    }

    EdgeDrawer(JFrame frame, boolean pinned, int width, int height) {
        this.frame = frame;
        desiredWidth = width;
        desiredHeight = height;
        // Move the full window instead of resizing its native input region on every tick.
        frame.setType(java.awt.Window.Type.UTILITY);

        handle.setFocusableWindowState(false);
        handle.setAutoRequestFocus(false);
        handle.setAlwaysOnTop(true);
        translucentHandle = handle.getGraphicsConfiguration().getDevice()
                .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.TRANSLUCENT);
        if (translucentHandle) {
            handle.setOpacity(IDLE_OPACITY);
        }
        arrow = new JButton() {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(getModel().isRollover() ? ClipboardTheme.ACCENT_SURFACE
                        : ClipboardTheme.BACKGROUND);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(ClipboardTheme.BORDER);
                g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                g.setColor(ClipboardTheme.ACCENT);
                g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int x = getWidth() / 2;
                int y = getHeight() / 2;
                int direction = (rightSide ? -1 : 1) * (open ? -1 : 1);
                g.drawLine(x - 3 * direction, y - 8, x + 3 * direction, y);
                g.drawLine(x + 3 * direction, y, x - 3 * direction, y + 8);
                g.dispose();
            }
        };
        arrow.setFocusable(false);
        arrow.setContentAreaFilled(false);
        arrow.setBorderPainted(false);
        arrow.setRolloverEnabled(true);
        arrow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        hoverDelay = new Timer(HOVER_DELAY_MS, event -> {
            if (!open && !dragging && dragPress == null && !suppressHover
                    && isPointerInHandle()) {
                openDrawer();
            }
        });
        hoverDelay.setRepeats(false);
        MouseAdapter handleMouse = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                setHandleHovered(true);
                if (!open && !dragging && !suppressHover) {
                    hoverDelay.restart();
                }
            }

            @Override
            public void mouseExited(MouseEvent event) {
                if (!dragging) setHandleHovered(false);
                hoverDelay.stop();
            }

            @Override
            public void mousePressed(MouseEvent event) {
                beginDrag(event, false);
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                dragHandle(event.getLocationOnScreen());
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                endDrag(event);
            }
        };
        arrow.addMouseListener(handleMouse);
        arrow.addMouseMotionListener(handleMouse);
        arrow.addActionListener(event -> {
            if (skipClick || dragging) return;
            if (open) hideDrawer();
            else openDrawer();
        });
        handle.add(arrow, BorderLayout.CENTER);
        animation = new Timer(8, event -> animate());
        animation.setInitialDelay(0);
        animation.setCoalesce(true);
        snapAnimation = new Timer(8, event -> animateSnap());
        snapAnimation.setInitialDelay(0);
        snapAnimation.setCoalesce(true);
        pointerWatcher = new Timer(40, event -> checkPointer());
        updateScreen();
        hideImmediately();
        // Initial hover should open normally; suppression is only for dismissals.
        suppressHover = false;
        pointerWatcher.start();
        setPinned(pinned);
    }

    void refreshTheme() {
        handle.repaint();
    }

    int panelWidth() { return panelWidth; }

    boolean canResizeFrom(ResizeEdge edge) {
        return edge == ResizeEdge.TOP || edge == ResizeEdge.BOTTOM
                || (rightSide ? edge == ResizeEdge.LEFT : edge == ResizeEdge.RIGHT);
    }

    void setResizeListener(BiConsumer<Integer, Integer> listener) { onResized = listener; }

    void beginResize(ResizeEdge edge, Point pointer) {
        if (closed || !open || dragging || resizing || !canResizeFrom(edge)) return;
        animation.stop();
        snapAnimation.stop();
        visibleWidth = panelWidth;
        resizeEdge = edge;
        resizePress = pointer;
        resizeWidthAtPress = panelWidth;
        resizeHeightAtPress = panelHeight;
        resizeTopAtPress = panelY;
        resizing = true;
        clickedInside = true;
        hoverDelay.stop();
        positionWindows();
    }

    void dragResize(Point pointer) {
        if (!resizing) return;
        int maximumWidth = Math.max(1, Math.min(WindowSizeStore.MAX_WIDTH, screen.width - HANDLE_WIDTH));
        int minimumWidth = Math.min(WindowSizeStore.MIN_WIDTH, maximumWidth);
        int maximumHeight = Math.max(1, Math.min(WindowSizeStore.MAX_HEIGHT, screen.height - 24));
        int minimumHeight = Math.min(WindowSizeStore.MIN_HEIGHT, maximumHeight);
        if (resizeEdge == ResizeEdge.LEFT || resizeEdge == ResizeEdge.RIGHT) {
            int movement = pointer.x - resizePress.x;
            panelWidth = clamp(resizeWidthAtPress + (rightSide ? -movement : movement),
                    minimumWidth, maximumWidth);
            desiredWidth = panelWidth;
            visibleWidth = panelWidth;
        } else if (resizeEdge == ResizeEdge.TOP) {
            int bottom = resizeTopAtPress + resizeHeightAtPress;
            int limit = Math.min(maximumHeight, bottom - screen.y);
            panelHeight = clamp(resizeHeightAtPress + resizePress.y - pointer.y,
                    Math.min(minimumHeight, limit), limit);
            panelY = bottom - panelHeight;
            desiredHeight = panelHeight;
        } else {
            int limit = Math.min(maximumHeight, screen.y + screen.height - resizeTopAtPress);
            panelHeight = clamp(resizeHeightAtPress + pointer.y - resizePress.y,
                    Math.min(minimumHeight, limit), limit);
            panelY = resizeTopAtPress;
            desiredHeight = panelHeight;
        }
        handleY = panelY + (panelHeight - HANDLE_HEIGHT) / 2;
        frame.setSize(panelWidth, panelHeight);
        positionWindows();
        frame.revalidate();
    }

    void endResize(Point pointer) {
        if (!resizing) return;
        dragResize(pointer);
        resizing = false;
        resizePress = null;
        if (onResized != null && (panelWidth != resizeWidthAtPress
                || panelHeight != resizeHeightAtPress)) onResized.accept(panelWidth, panelHeight);
    }

    void resize(int width, int height) {
        if (closed) return;
        desiredWidth = width;
        desiredHeight = height;
        animation.stop();
        snapAnimation.stop();
        dragging = false;
        resizing = false;
        dragPress = null;
        resizePress = null;
        skipClick = false;
        panelWidth = Math.max(1, Math.min(desiredWidth, screen.width - HANDLE_WIDTH));
        panelHeight = Math.max(1, Math.min(desiredHeight, screen.height - 24));
        handleY = clamp(handleY, screen.y, screen.y + screen.height - HANDLE_HEIGHT);
        panelY = clamp(handleY - (panelHeight - HANDLE_HEIGHT) / 2,
                screen.y, screen.y + screen.height - panelHeight);
        visibleWidth = open ? panelWidth : 0;
        frame.setSize(panelWidth, panelHeight);
        positionWindows();
        frame.revalidate();
        frame.repaint();
    }

    boolean isPinned() {
        return pinned;
    }

    void installHeaderDrag(Component header) {
        if (header instanceof AbstractButton) return;
        MouseAdapter headerMouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                beginDrag(event, true);
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                dragHandle(event.getLocationOnScreen());
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                endDrag(event);
            }
        };
        header.addMouseListener(headerMouse);
        header.addMouseMotionListener(headerMouse);
        header.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        if (header instanceof Container container) {
            for (Component child : container.getComponents()) {
                installHeaderDrag(child);
            }
        }
    }

    void setModalInteraction(boolean active) {
        modalInteraction = active;
        if (!active) checkPointer();
    }

    void setPinned(boolean pinned) {
        this.pinned = pinned;
        if (pinned) {
            showDrawer();
        }
    }

    void showDrawer() {
        if (closed) return;
        openDrawer();
    }

    private void openDrawer() {
        if (closed || dragging || resizing) return;
        if (snapAnimation.isRunning()) {
            snapAnimation.stop();
            positionWindows();
        }
        updateScreen();
        if (open) return;
        hoverDelay.stop();
        open = true;
        clickedInside = false;
        suppressHover = false;
        animateTo(panelWidth);
        updateArrow();
    }

    void hideDrawer() {
        if (closed || !open || resizing) return;
        hoverDelay.stop();
        open = false;
        clickedInside = false;
        suppressHover = true;
        animateTo(0);
        updateArrow();
    }

    void hideImmediately() {
        if (closed) return;
        hoverDelay.stop();
        animation.stop();
        snapAnimation.stop();
        dragging = false;
        resizing = false;
        dragPress = null;
        resizePress = null;
        skipClick = false;
        open = false;
        clickedInside = false;
        suppressHover = true;
        visibleWidth = 0;
        frame.setVisible(false);
        positionWindows();
        updateArrow();
        handle.setVisible(true);
    }

    private void animateTo(int target) {
        animationFrom = visibleWidth;
        animationTo = target;
        animationStarted = System.nanoTime();
        animationDuration = Math.max(MIN_SLIDE_NANOS,
                (target == 0 ? CLOSE_SLIDE_NANOS : FULL_SLIDE_NANOS)
                        * Math.abs(animationTo - animationFrom) / panelWidth);
        if (target > 0) {
            visibleWidth = Math.max(1, visibleWidth);
            positionWindows();
            frame.setVisible(true);
            frame.toFront();
        }
        animation.start();
    }

    private void animate() {
        double progress = Math.min(1.0,
                (System.nanoTime() - animationStarted) / (double) animationDuration);
        // Smoothstep starts and ends at zero speed, so the drawer eases at both edges.
        double eased = progress * progress * (3 - 2 * progress);
        int nextWidth = (int) Math.round(animationFrom + (animationTo - animationFrom) * eased);
        if (nextWidth != visibleWidth || progress >= 1.0) {
            visibleWidth = nextWidth;
            positionWindows();
        }
        if (progress >= 1.0) {
            animation.stop();
            if (!open) frame.setVisible(false);
        }
    }

    private void updateScreen() {
        GraphicsConfiguration configuration = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        Point anchor = handle.isShowing()
                ? new Point(handle.getX() + HANDLE_WIDTH / 2, handle.getY() + HANDLE_HEIGHT / 2)
                : new Point(configuration.getBounds().x + configuration.getBounds().width / 2,
                        configuration.getBounds().y + configuration.getBounds().height / 2);
        screen = screenFor(anchor);
        panelWidth = Math.max(1, Math.min(desiredWidth, screen.width - HANDLE_WIDTH));
        panelHeight = Math.max(1, Math.min(desiredHeight, screen.height - 24));
        if (handleY == Integer.MIN_VALUE) {
            panelY = screen.y + screen.height - panelHeight - 12;
            handleY = panelY + (panelHeight - HANDLE_HEIGHT) / 2;
        } else {
            handleY = clamp(handleY, screen.y, screen.y + screen.height - HANDLE_HEIGHT);
            panelY = clamp(handleY - (panelHeight - HANDLE_HEIGHT) / 2,
                    screen.y, screen.y + screen.height - panelHeight);
        }
        frame.setSize(panelWidth, panelHeight);
    }

    private void positionWindows() {
        int right = screen.x + screen.width;
        int shown = clamp(visibleWidth, 0, panelWidth);
        int handleAllowance = (int) Math.round(HANDLE_WIDTH * shown
                / (double) Math.max(1, panelWidth));
        frame.setLocation(rightSide ? right - shown - handleAllowance
                : screen.x - panelWidth + shown + handleAllowance, panelY);
        handle.setBounds(rightSide ? right - HANDLE_WIDTH : screen.x,
                handleY, HANDLE_WIDTH, HANDLE_HEIGHT);
    }

    private void dragHandle(Point pointer) {
        if (dragPress == null) return;
        int dx = pointer.x - dragPress.x;
        int dy = pointer.y - dragPress.y;
        if (!dragging && Math.abs(dx) + Math.abs(dy) < DRAG_THRESHOLD) return;
        if (!dragging) {
            dragging = true;
            skipClick = !dragFromHeader;
            hoverDelay.stop();
            animation.stop();
            snapAnimation.stop();
            suppressHover = true;
            if (dragOpenDrawer) {
                visibleWidth = panelWidth;
                positionWindows();
                frameAtPress = frame.getLocation();
                handleAtPress = handle.getLocation();
            } else {
                open = false;
                visibleWidth = 0;
                frame.setVisible(false);
                setHandleHovered(true);
            }
            updateArrow();
        }
        if (dragOpenDrawer) {
            frame.setLocation(frameAtPress.x + dx, frameAtPress.y + dy);
        }
        handle.setLocation(handleAtPress.x + dx, handleAtPress.y + dy);
    }

    private void beginDrag(MouseEvent event, boolean fromHeader) {
        if (closed || !SwingUtilities.isLeftMouseButton(event) || resizing) return;
        if (fromHeader) clickedInside = true;
        dragPress = event.getLocationOnScreen();
        if (!fromHeader) {
            lastArrowPress = dragPress;
            lastArrowPressAt = System.nanoTime();
        }
        dragFromHeader = fromHeader;
        dragOpenDrawer = fromHeader || open;
        handleAtPress = handle.getLocation();
        frameAtPress = frame.getLocation();
        hoverDelay.stop();
    }

    private void endDrag(MouseEvent event) {
        if (dragging) {
            dragHandle(event.getLocationOnScreen());
            snapHandle(event.getLocationOnScreen());
            if (!dragFromHeader) SwingUtilities.invokeLater(() -> skipClick = false);
        }
        dragPress = null;
        handleAtPress = null;
        frameAtPress = null;
        dragFromHeader = false;
        dragOpenDrawer = false;
    }

    private void snapHandle(Point pointer) {
        screen = screenFor(pointer);
        panelWidth = Math.max(1, Math.min(desiredWidth, screen.width - HANDLE_WIDTH));
        panelHeight = Math.max(1, Math.min(desiredHeight, screen.height - 24));
        rightSide = pointer.x >= screen.x + screen.width / 2;
        handleY = clamp(handle.getY(), screen.y, screen.y + screen.height - HANDLE_HEIGHT);
        panelY = clamp(handleY - (panelHeight - HANDLE_HEIGHT) / 2,
                screen.y, screen.y + screen.height - panelHeight);
        frame.setSize(panelWidth, panelHeight);
        snapOpenDrawer = dragOpenDrawer;
        open = snapOpenDrawer;
        visibleWidth = snapOpenDrawer ? panelWidth : 0;
        snapFromX = handle.getX();
        snapToX = rightSide ? screen.x + screen.width - HANDLE_WIDTH : screen.x;
        snapFromY = handle.getY();
        snapToY = handleY;
        snapFrameFromX = frame.getX();
        snapFrameFromY = frame.getY();
        snapFrameToX = rightSide ? screen.x + screen.width - panelWidth - HANDLE_WIDTH
                : screen.x + HANDLE_WIDTH;
        snapFrameToY = panelY;
        snapStarted = System.nanoTime();
        dragging = false;
        dragPress = null;
        snapAnimation.start();
        updateArrow();
    }

    private void animateSnap() {
        double progress = Math.min(1.0, (System.nanoTime() - snapStarted) / (double) SNAP_NANOS);
        double eased = progress * progress * (3 - 2 * progress);
        handle.setLocation((int) Math.round(snapFromX + (snapToX - snapFromX) * eased),
                (int) Math.round(snapFromY + (snapToY - snapFromY) * eased));
        if (snapOpenDrawer) {
            frame.setLocation((int) Math.round(snapFrameFromX
                    + (snapFrameToX - snapFrameFromX) * eased),
                    (int) Math.round(snapFrameFromY
                            + (snapFrameToY - snapFrameFromY) * eased));
        }
        if (progress >= 1.0) {
            snapAnimation.stop();
            positionWindows();
            setHandleHovered(isPointerInHandle());
            if (!snapOpenDrawer && pinned) {
                showDrawer();
            }
        }
    }

    private Rectangle screenFor(Point point) {
        Rectangle nearest = null;
        long nearestDistance = Long.MAX_VALUE;
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getScreenDevices()) {
            GraphicsConfiguration configuration = device.getDefaultConfiguration();
            Rectangle bounds = configuration.getBounds();
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
            Rectangle usable = new Rectangle(bounds.x + insets.left, bounds.y + insets.top,
                    bounds.width - insets.left - insets.right,
                    bounds.height - insets.top - insets.bottom);
            int x = clamp(point.x, usable.x, usable.x + usable.width - 1);
            int y = clamp(point.y, usable.y, usable.y + usable.height - 1);
            long distance = (long) (point.x - x) * (point.x - x)
                    + (long) (point.y - y) * (point.y - y);
            if (distance < nearestDistance) {
                nearest = usable;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private void checkPointer() {
        if (closed || dragging || resizing || snapAnimation.isRunning()) return;
        if (MouseInfo.getPointerInfo() == null) return;
        Point pointer = MouseInfo.getPointerInfo().getLocation();
        boolean inHandle = handle.getBounds().contains(pointer);
        setHandleHovered(inHandle);
        if (!inHandle) suppressHover = false;
    }

    void onGlobalClick(Point location) {
        if (closed || !open || dragging || resizing || snapAnimation.isRunning()) return;
        if (lastArrowPress != null
                && System.nanoTime() - lastArrowPressAt < 2_000_000_000L
                && lastArrowPress.distance(location) < 5) {
            lastArrowPress = null;
            return;
        }
        if (modalInteraction || frame.getBounds().contains(location)) {
            clickedInside = true;
        } else if (!handle.getBounds().contains(location) && !clickedInside && !pinned) {
            hideDrawer();
        }
    }

    private boolean isPointerInHandle() {
        return MouseInfo.getPointerInfo() != null
                && handle.getBounds().contains(MouseInfo.getPointerInfo().getLocation());
    }

    private void setHandleHovered(boolean hovered) {
        if (handleHovered == hovered) return;
        handleHovered = hovered;
        if (translucentHandle) {
            handle.setOpacity(hovered ? 1.0f : IDLE_OPACITY);
        }
    }

    private void updateArrow() {
        String description = open ? "Hide nashclip or drag to another edge"
                : "Drag to either screen edge, or hover to open nashclip";
        arrow.setToolTipText(description);
        arrow.getAccessibleContext().setAccessibleName(description);
        arrow.repaint();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        hoverDelay.stop();
        animation.stop();
        snapAnimation.stop();
        pointerWatcher.stop();
        onResized = null;
        dragPress = null;
        resizePress = null;
        handle.dispose();
        frame.dispose();
    }
}
