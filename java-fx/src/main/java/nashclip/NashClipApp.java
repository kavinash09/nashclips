package nashclip;

import java.awt.CheckboxMenuItem;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/** Connects the JavaFX view to clipboard, storage, shortcut, and paste services. */
public final class NashClipApp extends Application {
    private final Path settingsDirectory = DataDirectory.prepare();
    private final DrawerPositionStore drawerPositionStore = new DrawerPositionStore(settingsDirectory);
    private final WindowSizeStore sizeStore = new WindowSizeStore(settingsDirectory);
    private WindowSizeStore.Size windowSize = WindowSizeStore.factory();
    private final ObservableList<String> history = FXCollections.observableArrayList();
    private final GlobalHotkey hotkey = new GlobalHotkey();
    private final PasteService pasteService = new PasteService();
    private HistoryStore historyStore;
    private final java.util.List<String> pinnedClips = new ArrayList<>();
    private ClipboardWatcher clipboardWatcher;
    private HistoryGrid historyGrid;
    private EdgeDrawer drawer;
    private Stage panel;
    private TrayIcon trayIcon;
    private CheckboxMenuItem trayPinItem;
    private CheckboxMenuItem trayDarkItem;
    private Label countLabel;
    private Label statusLabel;
    private Label limitLabel;
    private Label shortcutLabel;
    private ToggleButton pinButton;
    private Button themeButton;
    private HBox dragHeader;
    private int historyLimit;
    private int sizeNoticeSeconds = 4;
    private SizeNotice sizeNotice;
    private boolean pinned;
    private String theme;
    private boolean settingsLoadFailed;
    private boolean historyLoadFailed;
    private boolean useInProgress;
    private boolean stopped;
    private Thread pasteWorker;
    private Thread shutdownHook;
    private InstanceLock instanceLock;

    @Override
    public void start(Stage stage) {
        Platform.setImplicitExit(false);
        try {
            instanceLock = new InstanceLock(settingsDirectory);
        } catch (IOException error) {
            new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR,
                    error.getMessage()).showAndWait();
            Platform.exit();
            return;
        }
        loadSettingsAndHistory();
        try {
            windowSize = sizeStore.load();
        } catch (IOException error) {
            System.err.println("Could not load window size: " + error.getMessage());
        }
        clipboardWatcher = new ClipboardWatcher(this::addCopiedText, units -> sizeNotice.showSize(units, sizeNoticeSeconds));
        panel = stage;
        panel.initStyle(StageStyle.TRANSPARENT);
        panel.setAlwaysOnTop(true);
        panel.setTitle("nashclip");
        panel.setOnCloseRequest(event -> { event.consume(); Platform.exit(); });
        panel.setScene(createScene());
        DrawerPositionStore.Position savedPosition = null;
        try {
            savedPosition = drawerPositionStore.load().orElse(null);
        } catch (IOException error) {
            System.err.println("Could not load drawer position: " + error.getMessage());
        }
        drawer = new EdgeDrawer(panel, dragHeader, pinned, savedPosition,
                windowSize.width(), windowSize.height(), this::saveDrawerPosition);
        drawer.setResizeListener(this::saveResizedWindow);
        historyGrid.setAvailableWidth(drawer.panelWidth());
        panel.widthProperty().addListener((ignored, before, after) ->
                historyGrid.setAvailableWidth(after.doubleValue()));
        drawer.setTheme(theme);
        installTray();
        clipboardWatcher.start();
        pasteService.start();
        shutdownHook = new Thread(() -> { hotkey.close(); pasteService.close(); }, "nashclip-fx-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        startHotkey();
    }

    @Override
    public void stop() {
        if (stopped) return;
        stopped = true;
        if (shutdownHook != null) {
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch (IllegalStateException shuttingDown) { }
        }
        if (pasteWorker != null) pasteWorker.interrupt();
        if (clipboardWatcher != null) clipboardWatcher.stop();
        if (sizeNotice != null) sizeNotice.close();
        hotkey.close();
        pasteService.close();
        if (trayIcon != null) SystemTray.getSystemTray().remove(trayIcon);
        if (panel != null) panel.setOnCloseRequest(null);
        if (drawer != null) drawer.close();
        if (instanceLock != null) {
            try { instanceLock.close(); } catch (IOException error) { System.err.println(error.getMessage()); }
        }
    }

    private void loadSettingsAndHistory() {
        AppSettings settings;
        try {
            settings = AppSettings.load(settingsDirectory);
        } catch (IOException error) {
            settingsLoadFailed = true;
            settings = new AppSettings(AppSettings.DEFAULT_HISTORY_LIMIT);
            System.err.println("Could not load settings: " + error.getMessage());
        }
        historyLimit = settings.historyLimit();
        sizeNoticeSeconds = settings.sizeNoticeSeconds();
        pinned = settings.pinned();
        theme = settings.theme();
        historyStore = new HistoryStore(settingsDirectory, historyLimit);
        try {
            HistoryStore.Snapshot saved = historyStore.loadSnapshot();
            pinnedClips.addAll(saved.pinned());
            history.addAll(saved.entries());
            persistHistory();
        } catch (IOException error) {
            historyLoadFailed = true;
            System.err.println("Could not load history: " + error.getMessage());
        }
    }

    private Scene createScene() {
        Label logo = new Label("▣");
        logo.getStyleClass().add("logo");
        Label title = new Label("nashclip");
        title.getStyleClass().add("app-title");
        countLabel = new Label(countText());
        countLabel.getStyleClass().add("subtle");
        VBox titles = new VBox(2, title, countLabel);
        Button live = new Button("● Live");
        live.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        live.setTooltip(new javafx.scene.control.Tooltip("Pause or resume capture for this session"));
        live.setOnAction(event -> {
            clipboardWatcher.setPaused(!clipboardWatcher.isPaused());
            live.setText(clipboardWatcher.isPaused() ? "Ⅱ Paused" : "● Live");
            statusLabel.setText(clipboardWatcher.isPaused() ? "Capture paused until resumed or restarted."
                    : "Capture resumed. Text copied while paused is skipped.");
        });
        live.getStyleClass().add("live-label");
        shortcutLabel = new Label("⌃⌥V  Show clips");
        shortcutLabel.getStyleClass().add("subtle");
        VBox indicators = new VBox(2, live, shortcutLabel);
        indicators.setAlignment(Pos.CENTER_RIGHT);
        pinButton = new ToggleButton("⌁");
        pinButton.getStyleClass().add("pin-button");
        pinButton.setSelected(pinned);
        updatePinButton();
        pinButton.setOnAction(event -> applyPin(pinButton.isSelected()));
        themeButton = new Button();
        themeButton.getStyleClass().add("theme-button");
        updateThemeButton();
        themeButton.setOnAction(event -> applyTheme(theme.equals("dark") ? "light" : "dark"));
        Button sizeButton = new Button("⤢");
        sizeButton.getStyleClass().add("theme-button");
        sizeButton.setAccessibleText("Change window size");
        sizeButton.setTooltip(new javafx.scene.control.Tooltip("Change window size"));
        sizeButton.setOnAction(event -> editWindowSize());
        Button hideButton = windowButton("−", "Hide to edge (keeps capturing)", () -> drawer.hideDrawer());
        Button quitButton = windowButton("×", "Quit nashclip (stops capturing)", Platform::exit);
        HBox header = new HBox(10, logo, titles, sizeButton, themeButton, pinButton, hideButton, quitButton, indicators);
        header.setId("dragHeader");
        dragHeader = header;
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titles, Priority.ALWAYS);
        header.getStyleClass().add("app-header");

        historyGrid = new HistoryGrid(history, this::createTile);
        statusLabel = new Label(settingsLoadFailed ? "Settings unavailable; history saving paused."
                : pinned ? "Pinned open. Use the arrow to close."
                : "Use pastes and hides this window.");
        statusLabel.getStyleClass().add("subtle");
        limitLabel = new Label((settingsLoadFailed ? "Saving paused" : "Local text history")
                + "  ·  " + historyLimit + " clip limit");
        limitLabel.getStyleClass().add("subtle");
        HBox footer = new HBox(12, statusLabel, limitLabel);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("app-footer");
        HBox.setHgrow(statusLabel, Priority.ALWAYS);

        BorderPane root = new BorderPane(historyGrid);
        root.setTop(header);
        root.setBottom(footer);
        root.getStyleClass().add("app-root");
        if (theme.equals("dark")) root.getStyleClass().add("theme-dark");
        root.setPadding(new Insets(10, 14, 8, 14));
        installResizeGrips(root);
        sizeNotice = new SizeNotice(panel, root);
        Scene scene = new Scene(root, windowSize.width(), windowSize.height(), Color.TRANSPARENT);
        scene.getStylesheets().add(getClass().getResource("/nashclip/style.css").toExternalForm());
        return scene;
    }

    private ClipboardTile createTile(int index) {
        String text = history.get(index);
        return new ClipboardTile(text, index, pinnedClips.contains(text), () -> toggleClipPin(text),
                () -> useClip(text), () -> {
                    var next = new ArrayList<>(history);
                    next.remove(index);
                    var pins = new ArrayList<>(pinnedClips);
                    pins.remove(text);
                    commitHistory(next, pins);
                });
    }

    private void toggleClipPin(String text) {
        var next = new ArrayList<>(pinnedClips);
        boolean removing = next.remove(text);
        if (!removing) {
            if (next.size() == HistoryPolicy.MAX_PINNED) {
                statusLabel.setText("Only 5 clips can be pinned. Unpin one first.");
                historyGrid.refresh();
                return;
            }
            next.add(0, text);
        }
        if (commitHistory(new ArrayList<>(history), next))
            statusLabel.setText(removing ? "Clip unpinned." : "Clip pinned · " + next.size() + "/5");
        else historyGrid.refresh();
    }

    private boolean commitHistory(java.util.List<String> entries, java.util.List<String> pins) {
        if (settingsLoadFailed || historyLoadFailed) {
            statusLabel.setText("Cannot save clips until the data-file error is fixed.");
            return false;
        }
        var bounded = HistoryPolicy.withPins(entries, pins, historyLimit);
        try {
            historyStore.saveSnapshot(new HistoryStore.Snapshot(bounded, pins));
            pinnedClips.clear();
            pinnedClips.addAll(pins);
            history.setAll(bounded);
            refreshHistory();
            return true;
        } catch (IOException error) {
            statusLabel.setText("Could not save clip changes. Nothing was changed.");
            return false;
        }
    }

    private void addCopiedText(String text) {
        if (stopped || !HistoryPolicy.accepts(text)) return;
        if (pinnedClips.contains(text)) return;
        history.add(0, text);
        refreshHistory();
        persistHistory();
    }

    private void refreshHistory() {
        var bounded = HistoryPolicy.withPins(history, pinnedClips, historyLimit);
        if (!bounded.equals(history)) history.setAll(bounded);
        historyGrid.refresh();
        countLabel.setText(countText());
    }

    private String countText() {
        return history.size() + (history.size() == 1 ? " clip" : " clips") + " · " + pinnedClips.size() + "/5 pinned";
    }

    private boolean persistHistory() {
        if (settingsLoadFailed || historyLoadFailed) return false;
        try {
            historyStore.saveSnapshot(new HistoryStore.Snapshot(new ArrayList<>(history), pinnedClips));
            return true;
        } catch (IOException error) {
            System.err.println("Could not save history: " + error.getMessage());
            if (statusLabel != null) statusLabel.setText("Could not save history: " + error.getMessage());
            return false;
        }
    }

    private void saveDrawerPosition(DrawerPositionStore.Position position) {
        try {
            drawerPositionStore.save(position);
            statusLabel.setText("Drawer position saved.");
        } catch (IOException error) {
            statusLabel.setText("Could not save drawer position.");
            System.err.println("Could not save drawer position: " + error.getMessage());
        }
    }

    private void saveResizedWindow(int width, int height) {
        try {
            WindowSizeStore.Size next = windowSize.withCurrent(width, height);
            sizeStore.save(next);
            windowSize = next;
            statusLabel.setText("Window size saved.");
        } catch (IOException | IllegalArgumentException error) {
            statusLabel.setText("Could not save window size: " + error.getMessage());
        }
    }

    private void installResizeGrips(BorderPane root) {
        Rectangle left = resizeGrip(EdgeDrawer.ResizeEdge.LEFT);
        left.heightProperty().bind(root.heightProperty());
        Rectangle right = resizeGrip(EdgeDrawer.ResizeEdge.RIGHT);
        right.xProperty().bind(root.widthProperty().subtract(7));
        right.heightProperty().bind(root.heightProperty());
        Rectangle top = resizeGrip(EdgeDrawer.ResizeEdge.TOP);
        top.widthProperty().bind(root.widthProperty());
        Rectangle bottom = resizeGrip(EdgeDrawer.ResizeEdge.BOTTOM);
        bottom.yProperty().bind(root.heightProperty().subtract(7));
        bottom.widthProperty().bind(root.widthProperty());
        root.getChildren().addAll(left, right, top, bottom);
    }

    private Rectangle resizeGrip(EdgeDrawer.ResizeEdge edge) {
        boolean horizontal = edge == EdgeDrawer.ResizeEdge.LEFT
                || edge == EdgeDrawer.ResizeEdge.RIGHT;
        Rectangle grip = new Rectangle(horizontal ? 7 : 1, horizontal ? 1 : 7,
                Color.TRANSPARENT);
        grip.setManaged(false);
        grip.setPickOnBounds(true);
        grip.setOnMouseEntered(event -> grip.setCursor(drawer != null && drawer.canResizeFrom(edge)
                ? horizontal ? Cursor.H_RESIZE : Cursor.V_RESIZE : Cursor.DEFAULT));
        grip.setOnMousePressed(event -> {
            if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY && drawer != null) {
                drawer.beginResize(edge, event.getScreenX(), event.getScreenY());
                event.consume();
            }
        });
        grip.setOnMouseDragged(event -> {
            if (drawer != null) drawer.dragResize(event.getScreenX(), event.getScreenY());
            event.consume();
        });
        grip.setOnMouseReleased(event -> {
            if (drawer != null) drawer.endResize(event.getScreenX(), event.getScreenY());
            event.consume();
        });
        return grip;
    }

    private Button windowButton(String label, String description, Runnable action) {
        Button button = new Button(label);
        button.getStyleClass().add("theme-button");
        button.setAccessibleText(description);
        button.setTooltip(new javafx.scene.control.Tooltip(description));
        button.setOnAction(event -> action.run());
        return button;
    }

    private void useClip(String text) {
        if (stopped || useInProgress) return;
        try {
            clipboardWatcher.copy(text);
        } catch (RuntimeException error) {
            statusLabel.setText("Clipboard unavailable. Try Use again.");
            return;
        }
        useInProgress = true;
        statusLabel.setText("Copied; restoring previous app...");
        pasteWorker = new Thread(() -> {
            pasteService.rememberFrontmostApplication();
            PasteService.Target target = pasteService.target();
            Platform.runLater(() -> {
                if (stopped) return;
                boolean accepted = drawer.hideDrawer(() -> {
                    if (stopped) return;
                    pasteWorker = new Thread(() -> {
                        PasteService.Result result;
                        try { result = pasteService.paste(target, text); }
                        catch (RuntimeException error) {
                            result = new PasteService.Result(false, "Copied, but paste failed.");
                        }
                        PasteService.Result outcome = result;
                        Platform.runLater(() -> {
                            useInProgress = false;
                            if (stopped) return;
                            statusLabel.setText(outcome.message());
                            if (!outcome.sent() || drawer.isPinned()) drawer.showDrawer();
                        });
                    }, "nashclip-fx-paste");
                    pasteWorker.setDaemon(true);
                    pasteWorker.start();
                });
                if (!accepted) {
                    pasteService.cancel(target);
                    useInProgress = false;
                    statusLabel.setText("Copied. Finish moving or resizing the drawer, then try Use again.");
                }
            });
        }, "nashclip-fx-focus");
        pasteWorker.setDaemon(true);
        pasteWorker.start();
    }

    private void startHotkey() {
        hotkey.start(() -> {
            pasteService.rememberFrontmostApplication();
            Platform.runLater(drawer::showDrawer);
        }, point -> Platform.runLater(() -> drawer.onGlobalClick(point)),
        () -> Platform.runLater(() -> shortcutLabel.setText("⌃⌥V  Show clips")),
        error -> Platform.runLater(() -> {
            shortcutLabel.setText("Shortcut unavailable");
            statusLabel.setText("Shortcut unavailable. Open nashclip from the menu bar.");
            System.err.println("Global shortcut unavailable: " + error);
        }), () -> Platform.runLater(() -> {
            statusLabel.setText("Outside-click detection unavailable. Use the arrow to close.");
            System.err.println("Outside-click detection unavailable.");
        }));
    }

    private void applyPin(boolean value) {
        pinned = value;
        pinButton.setSelected(value);
        updatePinButton();
        drawer.setPinned(value);
        if (trayPinItem != null) java.awt.EventQueue.invokeLater(() -> trayPinItem.setState(value));
        if (trayIcon != null) java.awt.EventQueue.invokeLater(() -> {
            trayIcon.setImage(createTrayImage(value));
            trayIcon.setToolTip(value ? "nashclip — pinned open" : "nashclip");
        });
        try {
            AppSettings.savePinned(settingsDirectory, value);
            statusLabel.setText(value ? "Pinned open. Use the arrow to close."
                    : "Unpinned. Outside click closes until you click inside.");
        } catch (IOException error) {
            statusLabel.setText("Pin changed for this session; settings could not be saved.");
            System.err.println("Could not save pin setting: " + error.getMessage());
        }
    }

    private void updatePinButton() {
        pinButton.setAccessibleText(pinButton.isSelected() ? "Unpin nashclip" : "Pin nashclip open");
        pinButton.setText("⌖");
    }

    private void updateThemeButton() {
        themeButton.setText(theme.equals("dark") ? "☀" : "☾");
        themeButton.setAccessibleText(theme.equals("dark") ? "Switch to light mode" : "Switch to dark mode");
        themeButton.setTooltip(new javafx.scene.control.Tooltip(themeButton.getAccessibleText()));
    }

    private void setThemeView(String value) {
        theme = value;
        updateThemeButton();
        if (value.equals("dark")) panel.getScene().getRoot().getStyleClass().add("theme-dark");
        else panel.getScene().getRoot().getStyleClass().remove("theme-dark");
        drawer.setTheme(value);
        if (trayDarkItem != null) java.awt.EventQueue.invokeLater(
                () -> trayDarkItem.setState(value.equals("dark")));
        if (trayIcon != null) java.awt.EventQueue.invokeLater(
                () -> trayIcon.setImage(createTrayImage(pinned)));
    }

    private void applyTheme(String value) {
        if (theme.equals(value)) return;
        try {
            AppSettings current = AppSettings.load(settingsDirectory);
            new AppSettings(current.historyLimit(), current.pinned(), value, current.sizeNoticeSeconds()).save(settingsDirectory);
            setThemeView(value);
            statusLabel.setText(value.equals("dark") ? "Dark theme on." : "Light theme on.");
        } catch (IOException error) {
            if (trayDarkItem != null) java.awt.EventQueue.invokeLater(
                    () -> trayDarkItem.setState(theme.equals("dark")));
            statusLabel.setText("Could not save theme: " + error.getMessage());
        }
    }

    private void editSettings() {
        drawer.setModalInteraction(true);
        drawer.showDrawer();
        try {
            Spinner<Integer> limitInput = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                    1, HistoryPolicy.MAX_ENTRIES, historyLimit));
            limitInput.setEditable(true);
            CheckBox pinnedInput = new CheckBox("Keep drawer open");
            pinnedInput.setSelected(pinned);
            CheckBox darkInput = new CheckBox("Dark mode");
            darkInput.setSelected(theme.equals("dark"));
            VBox options = new VBox(10, new Label("Maximum clips in history:"), limitInput,
                    pinnedInput, darkInput, new Label("A lower limit removes the oldest clips."));
            options.setPadding(new Insets(12));
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.initOwner(panel);
            dialog.setTitle("nashclip Settings");
            dialog.getDialogPane().setContent(options);
            dialog.getDialogPane().getStylesheets().add(
                    getClass().getResource("/nashclip/style.css").toExternalForm());
            if (theme.equals("dark")) dialog.getDialogPane().getStyleClass().add("theme-dark");
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
            int newLimit;
            try {
                newLimit = Integer.parseInt(limitInput.getEditor().getText().trim());
                if (newLimit < 1 || newLimit > HistoryPolicy.MAX_ENTRIES) throw new NumberFormatException();
            } catch (NumberFormatException error) {
                statusLabel.setText("Enter a positive whole number for the history limit.");
                return;
            }
            try {
                AppSettings current = AppSettings.load(settingsDirectory);
                sizeNoticeSeconds = current.sizeNoticeSeconds();
                new AppSettings(newLimit, pinnedInput.isSelected(),
                        darkInput.isSelected() ? "dark" : "light", sizeNoticeSeconds).save(settingsDirectory);
                settingsLoadFailed = false;
                historyLimit = newLimit;
                historyStore = new HistoryStore(settingsDirectory, newLimit);
                pinned = pinnedInput.isSelected();
                pinButton.setSelected(pinned);
                updatePinButton();
                drawer.setPinned(pinned);
                setThemeView(darkInput.isSelected() ? "dark" : "light");
                if (trayPinItem != null) java.awt.EventQueue.invokeLater(() -> trayPinItem.setState(pinned));
                if (trayIcon != null) java.awt.EventQueue.invokeLater(() -> trayIcon.setImage(createTrayImage(pinned)));
                refreshHistory();
                boolean saved = persistHistory();
                limitLabel.setText("Local text history  ·  " + newLimit + " clip limit");
                statusLabel.setText(saved ? "Settings saved." : "Settings saved; history could not be saved.");
            } catch (IOException error) {
                statusLabel.setText("Could not save settings: " + error.getMessage());
            }
        } finally {
            PauseTransition delay = new PauseTransition(Duration.millis(200));
            delay.setOnFinished(event -> drawer.setModalInteraction(false));
            delay.play();
        }
    }

    private void editWindowSize() {
        drawer.setModalInteraction(true);
        drawer.showDrawer();
        try {
            Spinner<Integer> width = new Spinner<>(WindowSizeStore.MIN_WIDTH,
                    WindowSizeStore.MAX_WIDTH, windowSize.width());
            Spinner<Integer> height = new Spinner<>(WindowSizeStore.MIN_HEIGHT,
                    WindowSizeStore.MAX_HEIGHT, windowSize.height());
            width.setEditable(true);
            height.setEditable(true);
            VBox options = new VBox(10, new Label("Width (pixels):"), width,
                    new Label("Height (pixels):"), height,
                    new Label("Saved default: " + windowSize.defaultWidth() + " × "
                            + windowSize.defaultHeight()));
            options.setPadding(new Insets(12));
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.initOwner(panel);
            dialog.setTitle("Window Size");
            dialog.getDialogPane().setContent(options);
            dialog.getDialogPane().getStylesheets().add(
                    getClass().getResource("/nashclip/style.css").toExternalForm());
            if (theme.equals("dark")) dialog.getDialogPane().getStyleClass().add("theme-dark");
            ButtonType apply = new ButtonType("Apply size", ButtonBar.ButtonData.APPLY);
            ButtonType saveDefault = new ButtonType("Save as default", ButtonBar.ButtonData.APPLY);
            ButtonType reset = new ButtonType("Reset to default", ButtonBar.ButtonData.APPLY);
            dialog.getDialogPane().getButtonTypes().addAll(apply, saveDefault, reset, ButtonType.CANCEL);
            ButtonType selected = dialog.showAndWait().orElse(ButtonType.CANCEL);
            if (selected == ButtonType.CANCEL) return;
            WindowSizeStore.Size next;
            if (selected == reset) {
                next = windowSize.resetToDefault();
            } else {
                int newWidth = Integer.parseInt(width.getEditor().getText().trim());
                int newHeight = Integer.parseInt(height.getEditor().getText().trim());
                next = windowSize.withCurrent(newWidth, newHeight);
                if (selected == saveDefault) next = next.saveCurrentAsDefault();
            }
            sizeStore.save(next);
            windowSize = next;
            drawer.resize(next.width(), next.height());
            historyGrid.setAvailableWidth(drawer.panelWidth());
            statusLabel.setText(selected == saveDefault ? "Default window size saved."
                    : selected == reset ? "Window size reset to default." : "Window size saved.");
        } catch (IllegalArgumentException error) {
            statusLabel.setText("Enter a width from 500–4096 and height from 260–2160.");
        } catch (IOException error) {
            statusLabel.setText("Could not save window size: " + error.getMessage());
        } finally {
            PauseTransition delay = new PauseTransition(Duration.millis(200));
            delay.setOnFinished(event -> drawer.setModalInteraction(false));
            delay.play();
        }
    }

    private void installTray() {
        if (!SystemTray.isSupported()) return;
        PopupMenu menu = new PopupMenu();
        MenuItem show = new MenuItem("Show nashclip (Control+Option+V)");
        show.addActionListener(event -> Platform.runLater(drawer::showDrawer));
        menu.add(show);
        trayPinItem = new CheckboxMenuItem("Pin Drawer Open", pinned);
        trayPinItem.addItemListener(event -> Platform.runLater(() -> applyPin(trayPinItem.getState())));
        menu.add(trayPinItem);
        trayDarkItem = new CheckboxMenuItem("Dark Mode", theme.equals("dark"));
        trayDarkItem.addItemListener(event -> Platform.runLater(
                () -> applyTheme(trayDarkItem.getState() ? "dark" : "light")));
        menu.add(trayDarkItem);
        MenuItem size = new MenuItem("Window Size...");
        size.addActionListener(event -> Platform.runLater(this::editWindowSize));
        menu.add(size);
        MenuItem settings = new MenuItem("Settings...");
        settings.addActionListener(event -> Platform.runLater(this::editSettings));
        menu.add(settings);
        menu.addSeparator();
        MenuItem quit = new MenuItem("Quit nashclip");
        quit.addActionListener(event -> Platform.runLater(Platform::exit));
        menu.add(quit);
        trayIcon = new TrayIcon(createTrayImage(pinned), pinned ? "nashclip — pinned open" : "nashclip", menu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(event -> Platform.runLater(drawer::showDrawer));
        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (java.awt.AWTException | SecurityException error) {
            trayIcon = null;
            System.err.println("Menu bar unavailable: " + error.getMessage());
        }
    }

    private BufferedImage createTrayImage(boolean pinned) {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        boolean dark = "dark".equals(theme);
        g.setColor(dark ? new java.awt.Color(91, 68, 36) : new java.awt.Color(236, 176, 50));
        g.fillRoundRect(5, 5, 22, 24, 7, 7);
        g.setColor(dark ? new java.awt.Color(43, 40, 35) : new java.awt.Color(255, 248, 218));
        g.fillRoundRect(8, 9, 16, 16, 3, 3);
        g.setColor(dark ? new java.awt.Color(255, 218, 128) : new java.awt.Color(155, 98, 22));
        g.fillRoundRect(11, 3, 10, 6, 3, 3);
        if (pinned) {
            g.setColor(dark ? new java.awt.Color(255, 218, 128) : new java.awt.Color(155, 98, 22));
            g.fillOval(20, 19, 9, 9);
        }
        g.dispose();
        return image;
    }
}
