package nashclip;

import java.awt.Point;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/** Owns the edge handle, sliding window, pin state, and drag-to-edge behavior. */
final class EdgeDrawer {
    enum ResizeEdge { LEFT, RIGHT, TOP, BOTTOM }
    private static final double HANDLE_WIDTH = 18;
    private static final double HANDLE_HEIGHT = 96;
    private static final double SLIDE_DURATION_MS = 340;

    private final Stage panel;
    private final Consumer<DrawerPositionStore.Position> onPositionChanged;
    private final Stage handle = new Stage(StageStyle.TRANSPARENT);
    private final Button arrow = new Button();
    private final PauseTransition hoverDelay = new PauseTransition(Duration.millis(180));
    private final DoubleProperty reveal = new SimpleDoubleProperty();
    private Timeline animation;
    private Rectangle2D screen;
    private double panelWidth;
    private double panelHeight;
    private double desiredWidth;
    private double desiredHeight;
    private double panelY;
    private double handleY = Double.NaN;
    private boolean rightSide = true;
    private boolean open;
    private boolean closed;
    private boolean pinned;
    private boolean clickedInside;
    private boolean suppressHover;
    private boolean modalInteraction;
    private boolean dragging;
    private boolean resizing;
    private ResizeEdge resizeEdge;
    private double resizePressX;
    private double resizePressY;
    private double resizeWidthAtPress;
    private double resizeHeightAtPress;
    private double resizeTopAtPress;
    private BiConsumer<Integer, Integer> onResized;
    private boolean skipClick;
    private double pressX;
    private double pressY;
    private double handleStartX;
    private double handleStartY;
    private double panelStartX;
    private double panelStartY;
    private boolean dragWasOpen;
    private boolean dragArmed;
    private Runnable pendingCloseAction;

    EdgeDrawer(Stage panel, Node dragHeader, boolean pinned,
            DrawerPositionStore.Position savedPosition, int width, int height,
            Consumer<DrawerPositionStore.Position> onPositionChanged) {
        this.panel = panel;
        desiredWidth = width;
        desiredHeight = height;
        this.onPositionChanged = onPositionChanged;
        arrow.getStyleClass().add("drawer-handle");
        arrow.setFocusTraversable(false);
        StackPane handleRoot = new StackPane(arrow);
        handleRoot.getStyleClass().add("handle-root");
        Scene scene = new Scene(handleRoot, HANDLE_WIDTH, HANDLE_HEIGHT, Color.TRANSPARENT);
        scene.getStylesheets().add(EdgeDrawer.class.getResource("/nashclip/style.css").toExternalForm());
        handle.setScene(scene);
        handle.setAlwaysOnTop(true);
        handle.setOpacity(0.66);
        handle.setWidth(HANDLE_WIDTH);
        handle.setHeight(HANDLE_HEIGHT);

        reveal.addListener((ignored, before, after) -> positionWindows());
        hoverDelay.setOnFinished(event -> {
            if (!open && !dragging && !suppressHover && arrow.isHover()) showDrawer();
        });
        arrow.setOnMouseEntered(event -> {
            handle.setOpacity(1);
            if (!open && !suppressHover) hoverDelay.playFromStart();
        });
        arrow.setOnMouseExited(event -> {
            handle.setOpacity(0.66);
            hoverDelay.stop();
            suppressHover = false;
        });
        arrow.setOnAction(event -> {
            if (skipClick || dragging) return;
            if (open) hideDrawer();
            else showDrawer();
        });
        installDrag(arrow, false);
        installDrag(dragHeader, true);
        panel.getScene().addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                event -> clickedInside = true);

        screen = savedPosition == null ? Screen.getPrimary().getVisualBounds()
                : savedScreen(savedPosition);
        if (savedPosition != null) {
            rightSide = savedPosition.rightSide();
            handleY = screen.getMinY()
                    + savedPosition.verticalFraction() * (screen.getHeight() - HANDLE_HEIGHT);
        }
        updateSize();
        if (savedPosition != null) {
            panelY = clamp(handleY - (panelHeight - HANDLE_HEIGHT) / 2,
                    screen.getMinY(), screen.getMaxY() - panelHeight);
        }
        reveal.set(0);
        positionWindows();
        handle.show();
        this.pinned = pinned;
        updateArrow();
        if (pinned) showDrawer();
    }

    boolean isPinned() {
        return pinned;
    }

    void setTheme(String theme) {
        if ("dark".equals(theme)) handle.getScene().getRoot().getStyleClass().add("theme-dark");
        else handle.getScene().getRoot().getStyleClass().remove("theme-dark");
    }

    void setPinned(boolean value) {
        pinned = value;
        if (value) showDrawer();
    }

    void setModalInteraction(boolean value) {
        modalInteraction = value;
    }

    double panelWidth() { return panelWidth; }

    boolean canResizeFrom(ResizeEdge edge) {
        return edge == ResizeEdge.TOP || edge == ResizeEdge.BOTTOM
                || (rightSide ? edge == ResizeEdge.LEFT : edge == ResizeEdge.RIGHT);
    }

    void setResizeListener(BiConsumer<Integer, Integer> listener) { onResized = listener; }

    void beginResize(ResizeEdge edge, double x, double y) {
        if (!open || dragging || resizing || !canResizeFrom(edge)) return;
        if (animation != null) animation.stop();
        reveal.set(panelWidth);
        resizeEdge = edge;
        resizePressX = x;
        resizePressY = y;
        resizeWidthAtPress = panelWidth;
        resizeHeightAtPress = panelHeight;
        resizeTopAtPress = panelY;
        resizing = true;
        clickedInside = true;
        hoverDelay.stop();
    }

    void dragResize(double x, double y) {
        if (!resizing) return;
        double maxWidth = Math.max(1, Math.min(WindowSizeStore.MAX_WIDTH,
                screen.getWidth() - HANDLE_WIDTH));
        double minWidth = Math.min(WindowSizeStore.MIN_WIDTH, maxWidth);
        double maxHeight = Math.max(1, Math.min(WindowSizeStore.MAX_HEIGHT,
                screen.getHeight() - 24));
        double minHeight = Math.min(WindowSizeStore.MIN_HEIGHT, maxHeight);
        if (resizeEdge == ResizeEdge.LEFT || resizeEdge == ResizeEdge.RIGHT) {
            panelWidth = clamp(resizeWidthAtPress + (rightSide ? resizePressX - x : x - resizePressX),
                    minWidth, maxWidth);
            desiredWidth = panelWidth;
            reveal.set(panelWidth);
        } else if (resizeEdge == ResizeEdge.TOP) {
            double bottom = resizeTopAtPress + resizeHeightAtPress;
            double limit = Math.min(maxHeight, bottom - screen.getMinY());
            panelHeight = clamp(resizeHeightAtPress + resizePressY - y,
                    Math.min(minHeight, limit), limit);
            panelY = bottom - panelHeight;
            desiredHeight = panelHeight;
        } else {
            double limit = Math.min(maxHeight, screen.getMaxY() - resizeTopAtPress);
            panelHeight = clamp(resizeHeightAtPress + y - resizePressY,
                    Math.min(minHeight, limit), limit);
            panelY = resizeTopAtPress;
            desiredHeight = panelHeight;
        }
        handleY = panelY + (panelHeight - HANDLE_HEIGHT) / 2;
        panel.setWidth(panelWidth);
        panel.setHeight(panelHeight);
        positionWindows();
    }

    void endResize(double x, double y) {
        if (!resizing) return;
        dragResize(x, y);
        resizing = false;
        if (panelWidth == resizeWidthAtPress && panelHeight == resizeHeightAtPress) return;
        double span = Math.max(1, screen.getHeight() - HANDLE_HEIGHT);
        onPositionChanged.accept(new DrawerPositionStore.Position(rightSide,
                screen.getMinX(), screen.getMinY(), screen.getWidth(), screen.getHeight(),
                (handleY - screen.getMinY()) / span));
        if (onResized != null) onResized.accept((int) Math.round(panelWidth),
                (int) Math.round(panelHeight));
    }

    void resize(int width, int height) {
        if (closed) return;
        if (pendingCloseAction != null) finishClose();
        dragging = false;
        resizing = false;
        dragArmed = false;
        skipClick = false;
        if (animation != null) animation.stop();
        desiredWidth = width;
        desiredHeight = height;
        updateSize();
        if (screen != null) {
            panelY = clamp(handleY - (panelHeight - HANDLE_HEIGHT) / 2,
                    screen.getMinY(), screen.getMaxY() - panelHeight);
            reveal.set(open ? panelWidth : 0);
            positionWindows();
        }
    }

    void showDrawer() {
        if (closed) return;
        if (dragging || resizing || pendingCloseAction != null) return;
        updateScreen(handle.getX() + HANDLE_WIDTH / 2, handle.getY() + HANDLE_HEIGHT / 2);
        if (open && reveal.get() == panelWidth) return;
        open = true;
        clickedInside = false;
        suppressHover = false;
        hoverDelay.stop();
        panel.show();
        panel.toFront();
        handle.toFront();
        animateTo(panelWidth, SLIDE_DURATION_MS);
        updateArrow();
    }

    void hideDrawer() {
        hideDrawer(null);
    }

    /** Slides closed, then runs the action after the panel has left the screen. */
    boolean hideDrawer(Runnable afterClose) {
        if (closed || resizing || dragging || (afterClose != null && pendingCloseAction != null)) return false;
        if (!open) {
            if (afterClose != null) {
                if (reveal.get() > 0) pendingCloseAction = afterClose;
                else afterClose.run();
            }
            return true;
        }
        pendingCloseAction = afterClose;
        open = false;
        clickedInside = false;
        suppressHover = true;
        hoverDelay.stop();
        animateTo(0, SLIDE_DURATION_MS);
        updateArrow();
        return true;
    }

    void onGlobalClick(Point point) {
        if (!open || dragging || resizing || modalInteraction) return;
        if (contains(panel, point)) {
            clickedInside = true;
        } else if (!contains(handle, point) && !clickedInside && !pinned) {
            hideDrawer();
        }
    }

    void close() {
        if (closed) return;
        closed = true;
        hoverDelay.stop();
        if (animation != null) animation.stop();
        pendingCloseAction = null;
        handle.close();
        panel.close();
    }

    private void animateTo(double target, double fullDuration) {
        if (animation != null) animation.stop();
        double distance = Math.abs(target - reveal.get());
        double duration = Math.max(120, fullDuration * distance / Math.max(1, panelWidth));
        animation = new Timeline(new KeyFrame(Duration.millis(duration),
                new javafx.animation.KeyValue(reveal, target, Interpolator.EASE_BOTH)));
        animation.setOnFinished(event -> {
            if (!open) {
                finishClose();
            }
        });
        animation.play();
    }

    private void finishClose() {
        if (animation != null) animation.stop();
        reveal.set(0);
        open = false;
        panel.hide();
        Runnable completed = pendingCloseAction;
        pendingCloseAction = null;
        if (!closed && completed != null) completed.run();
    }

    private void positionWindows() {
        if (screen == null || dragging) return;
        double left = screen.getMinX();
        double right = screen.getMaxX();
        double shown = clamp(reveal.get(), 0, panelWidth);
        double allowance = HANDLE_WIDTH * shown / Math.max(1, panelWidth);
        panel.setX(rightSide ? right - shown - allowance : left - panelWidth + shown + allowance);
        panel.setY(panelY);
        handle.setX(rightSide ? right - HANDLE_WIDTH : left);
        handle.setY(handleY);
    }

    private void updateScreen(double x, double y) {
        Rectangle2D chosen = screenAt(x, y);
        if (screen == null || !screen.equals(chosen)) {
            screen = chosen;
            updateSize();
        }
        handleY = clamp(handleY, screen.getMinY(), screen.getMaxY() - HANDLE_HEIGHT);
        panelY = clamp(handleY - (panelHeight - HANDLE_HEIGHT) / 2,
                screen.getMinY(), screen.getMaxY() - panelHeight);
    }

    private void updateSize() {
        panelWidth = Math.max(1, Math.min(desiredWidth, screen.getWidth() - HANDLE_WIDTH));
        panelHeight = Math.max(1, Math.min(desiredHeight, screen.getHeight() - 24));
        panel.setWidth(panelWidth);
        panel.setHeight(panelHeight);
        if (Double.isNaN(handleY)) {
            panelY = screen.getMaxY() - panelHeight - 12;
            handleY = panelY + (panelHeight - HANDLE_HEIGHT) / 2;
        }
    }

    private void installDrag(Node node, boolean fromHeader) {
        node.setOnMousePressed(event -> {
            dragArmed = false;
            if (closed || pendingCloseAction != null || event.getButton() != MouseButton.PRIMARY || resizing) return;
            if (fromHeader) {
                Node target = event.getTarget() instanceof Node ? (Node) event.getTarget() : null;
                for (; target != null && target != node; target = target.getParent()) {
                    if (target instanceof javafx.scene.control.ButtonBase) return;
                }
            }
            dragArmed = true;
            if (fromHeader) clickedInside = true;
            hoverDelay.stop();
            pressX = event.getScreenX();
            pressY = event.getScreenY();
            handleStartX = handle.getX();
            handleStartY = handle.getY();
            panelStartX = panel.getX();
            panelStartY = panel.getY();
            dragWasOpen = open || fromHeader;
            dragging = false;
        });
        node.setOnMouseDragged(event -> {
            if (!dragArmed || closed || pendingCloseAction != null || resizing) return;
            double dx = event.getScreenX() - pressX;
            double dy = event.getScreenY() - pressY;
            if (!dragging && Math.abs(dx) + Math.abs(dy) < 5) return;
            if (!dragging) {
                dragging = true;
                skipClick = !fromHeader;
                if (animation != null) animation.stop();
                if (!dragWasOpen) panel.hide();
            }
            handle.setX(handleStartX + dx);
            handle.setY(handleStartY + dy);
            if (dragWasOpen) {
                panel.setX(panelStartX + dx);
                panel.setY(panelStartY + dy);
            }
        });
        node.setOnMouseReleased(event -> {
            if (!dragArmed) return;
            dragArmed = false;
            if (!dragging) return;
            snapToEdge(event.getScreenX(), event.getScreenY());
            javafx.application.Platform.runLater(() -> skipClick = false);
        });
    }

    private void snapToEdge(double x, double y) {
        screen = screenAt(x, y);
        updateSize();
        rightSide = x >= screen.getMinX() + screen.getWidth() / 2;
        handleY = clamp(handle.getY(), screen.getMinY(), screen.getMaxY() - HANDLE_HEIGHT);
        panelY = clamp(handleY - (panelHeight - HANDLE_HEIGHT) / 2,
                screen.getMinY(), screen.getMaxY() - panelHeight);
        double span = Math.max(1, screen.getHeight() - HANDLE_HEIGHT);
        onPositionChanged.accept(new DrawerPositionStore.Position(rightSide,
                screen.getMinX(), screen.getMinY(), screen.getWidth(), screen.getHeight(),
                (handleY - screen.getMinY()) / span));
        open = dragWasOpen;
        double targetReveal = open ? panelWidth : 0;
        double targetHandleX = rightSide
                ? screen.getMaxX() - HANDLE_WIDTH : screen.getMinX();
        double targetPanelX = rightSide
                ? screen.getMaxX() - panelWidth - HANDLE_WIDTH : screen.getMinX() + HANDLE_WIDTH;
        double initialHandleX = handle.getX();
        double initialHandleY = handle.getY();
        double initialPanelX = panel.getX();
        double initialPanelY = panel.getY();
        DoubleProperty progress = new SimpleDoubleProperty();
        progress.addListener((ignored, before, after) -> {
            double amount = after.doubleValue();
            handle.setX(initialHandleX + (targetHandleX - initialHandleX) * amount);
            handle.setY(initialHandleY + (handleY - initialHandleY) * amount);
            if (open) {
                panel.setX(initialPanelX + (targetPanelX - initialPanelX) * amount);
                panel.setY(initialPanelY + (panelY - initialPanelY) * amount);
            }
        });
        animation = new Timeline(new KeyFrame(Duration.millis(220),
                new javafx.animation.KeyValue(progress, 1, Interpolator.EASE_BOTH)));
        animation.setOnFinished(event -> {
            dragging = false;
            reveal.set(targetReveal);
            positionWindows();
            if (!open && pinned) showDrawer();
        });
        animation.play();
        updateArrow();
    }

    private void updateArrow() {
        arrow.setText(rightSide ? (open ? "›" : "‹") : (open ? "‹" : "›"));
        arrow.setAccessibleText(open ? "Hide nashclip or drag to another edge"
                : "Drag to an edge, or hover to open nashclip");
    }

    private static boolean contains(Stage stage, Point point) {
        return stage.isShowing() && point.x >= stage.getX() && point.x < stage.getX() + stage.getWidth()
                && point.y >= stage.getY() && point.y < stage.getY() + stage.getHeight();
    }

    private static Rectangle2D screenAt(double x, double y) {
        var matches = Screen.getScreensForRectangle(x, y, 1, 1);
        return (matches.isEmpty() ? Screen.getPrimary() : matches.get(0)).getVisualBounds();
    }

    private static Rectangle2D savedScreen(DrawerPositionStore.Position position) {
        Rectangle2D nearest = Screen.getPrimary().getVisualBounds();
        double savedCenterX = position.screenX() + position.screenWidth() / 2;
        double savedCenterY = position.screenY() + position.screenHeight() / 2;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (Screen candidate : Screen.getScreens()) {
            Rectangle2D bounds = candidate.getVisualBounds();
            if (Math.abs(bounds.getMinX() - position.screenX()) < 2
                    && Math.abs(bounds.getMinY() - position.screenY()) < 2
                    && Math.abs(bounds.getWidth() - position.screenWidth()) < 2
                    && Math.abs(bounds.getHeight() - position.screenHeight()) < 2) {
                return bounds;
            }
            double dx = bounds.getMinX() + bounds.getWidth() / 2 - savedCenterX;
            double dy = bounds.getMinY() + bounds.getHeight() / 2 - savedCenterY;
            double distance = dx * dx + dy * dy;
            if (distance < nearestDistance) {
                nearest = bounds;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
