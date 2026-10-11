import java.awt.BorderLayout;
import java.awt.AWTException;
import java.awt.CheckboxMenuItem;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.Point;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.DefaultListModel;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

public class NashClip {
    private final Path settingsDirectory;
    private final InstanceLock instanceLock;
    private final WindowSizeStore sizeStore;
    private WindowSizeStore.Size windowSize;
    private int historyLimit;
    private int sizeNoticeSeconds = 4;
    private SizeNotice sizeNotice;
    private HistoryStore historyStore;
    private final List<String> pinnedClips = new ArrayList<>();
    private boolean settingsLoadFailed;
    private ClipboardWatcher clipboardWatcher;
    private boolean historyLoadFailed;
    private JLabel statusLabel;
    private JLabel countLabel;
    private JLabel limitLabel;
    private ClipboardTheme.PinButton pinButton;
    private JPanel dragHeader;
    private CheckboxMenuItem trayPinItem;
    private CheckboxMenuItem trayDarkItem;
    private DefaultListModel<String> historyModel;
    private HistoryGrid historyGrid;
    private boolean trayAvailable;
    private TrayIcon trayIcon;
    private EdgeDrawer edgeDrawer;
    private JFrame frame;
    private boolean stopped;
    private boolean useInProgress;
    private Thread pasteWorker;
    private Thread shutdownHook;
    private boolean started;
    private boolean pinned;
    private String theme;
    private final GlobalHotkey globalHotkey = new GlobalHotkey();
    private final PasteService pasteService = new PasteService();
    private JLabel shortcutLabel;
    private static final int TILE_WIDTH = 220;
    NashClip() throws IOException {
        Path directory = DataDirectory.prepare();
        instanceLock = new InstanceLock(directory);
        settingsDirectory = directory;
        sizeStore = new WindowSizeStore(directory);
        try {
            windowSize = sizeStore.load();
        } catch (IOException error) {
            windowSize = WindowSizeStore.factory();
            System.err.println("Could not load window size: " + error.getMessage());
        }
        AppSettings settings;
        try {
            settings = AppSettings.load(directory);
        } catch (IOException error) {
            settingsLoadFailed = true;
            settings = new AppSettings(AppSettings.DEFAULT_HISTORY_LIMIT);
            System.err.println("Could not load .nashclip/settings.json: " + error.getMessage());
        }
        historyLimit = settings.historyLimit();
        sizeNoticeSeconds = settings.sizeNoticeSeconds();
        pinned = settings.pinned();
        theme = settings.theme();
        ClipboardTheme.use(theme);
        historyStore = new HistoryStore(directory, historyLimit);
    }
    private void refreshGrid() {
        List<String> bounded = HistoryPolicy.withPins(historyEntries(), pinnedClips, historyLimit);
        if (!bounded.equals(historyEntries())) {
            historyModel.clear();
            bounded.forEach(historyModel::addElement);
        }
        historyGrid.refresh();
        if (countLabel != null) {
            countLabel.setText(historyModel.size() + (historyModel.size() == 1 ? " clip" : " clips")
                    + " · " + pinnedClips.size() + "/5 pinned");
        }
    }
    private void addCopiedText(String text) {
        if (stopped || !HistoryPolicy.accepts(text)) return;
        if (pinnedClips.contains(text)) return;
        historyModel.add(0, text);
        refreshGrid();
        persistHistory(historyModel);
    }
    public synchronized void run() {
        if (started || stopped) return;
        started = true;
        SwingUtilities.invokeLater(() -> {
            historyModel = loadHistoryModel();
            // Apply a reduced limit to saved history too, keeping the newest entries.
            persistHistory(historyModel);
            clipboardWatcher = new ClipboardWatcher(this::addCopiedText, units -> sizeNotice.showSize(units, sizeNoticeSeconds));
            frame = createJframe();
            sizeNotice = new SizeNotice();
            frame.setGlassPane(sizeNotice);
            historyGrid = new HistoryGrid(historyModel, this::createHistoryTile);
            frame.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent event) {
                    historyGrid.setAvailableWidth(frame.getWidth());
                }
            });
            frame.setContentPane(createContent(historyModel, historyGrid));
            frame.setAutoRequestFocus(false);
            frame.setFocusableWindowState(false);
            frame.setAlwaysOnTop(true);
            edgeDrawer = new EdgeDrawer(frame, pinned, windowSize.width(), windowSize.height());
            edgeDrawer.setResizeListener(this::saveResizedWindow);
            historyGrid.setAvailableWidth(edgeDrawer.panelWidth());
            edgeDrawer.installHeaderDrag(dragHeader);
            trayAvailable = installTray(frame);
            clipboardWatcher.start();
            pasteService.start();
            startGlobalHotkey(frame);
        });
    }

    private void startGlobalHotkey(JFrame frame) {
        shutdownHook = new Thread(() -> {
            globalHotkey.close();
            pasteService.close();
            try { instanceLock.close(); } catch (IOException ignored) { }
        }, "nashclip-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        globalHotkey.start(() -> {
            // Capture the destination before the panel appears; keep it focused for paste.
            pasteService.rememberFrontmostApplication();
            SwingUtilities.invokeLater(() -> showWindow(frame));
        }, point -> SwingUtilities.invokeLater(() -> edgeDrawer.onGlobalClick(point)),
        () -> SwingUtilities.invokeLater(() -> {
            shortcutLabel.setText(GlobalHotkey.DISPLAY + "  Show clips");
            shortcutLabel.setToolTipText("Control + Option + V opens nashclip from any app while it is running.");
        }), error -> {
            System.err.println("Global shortcut unavailable: " + error);
            SwingUtilities.invokeLater(() -> {
                shortcutLabel.setText("Shortcut unavailable");
                shortcutLabel.setToolTipText(error);
                statusLabel.setText("Shortcut unavailable. Open nashclip from the menu bar.");
                statusLabel.setToolTipText(error);
            });
        }, () -> SwingUtilities.invokeLater(() -> {
            System.err.println("Outside-click detection unavailable; use the drawer arrow to close.");
            statusLabel.setText("Outside-click detection unavailable. Use the arrow to close.");
        }));
    }

    private boolean installTray(JFrame frame) {
        if (!SystemTray.isSupported()) {
            return false;
        }
        PopupMenu menu = new PopupMenu();
        MenuItem show = new MenuItem("Show nashclip (Control+Option+V)");
        show.addActionListener(event -> SwingUtilities.invokeLater(() -> showWindow(frame)));
        menu.add(show);
        trayPinItem = new CheckboxMenuItem("Pin Drawer Open", pinned);
        trayPinItem.addItemListener(event -> {
            boolean selected = trayPinItem.getState();
            SwingUtilities.invokeLater(() -> applyPin(selected));
        });
        menu.add(trayPinItem);
        trayDarkItem = new CheckboxMenuItem("Dark Mode", theme.equals("dark"));
        trayDarkItem.addItemListener(event -> SwingUtilities.invokeLater(
                () -> applyTheme(trayDarkItem.getState() ? "dark" : "light")));
        menu.add(trayDarkItem);
        MenuItem windowSizeItem = new MenuItem("Window Size...");
        windowSizeItem.addActionListener(event -> SwingUtilities.invokeLater(this::showSizeDialog));
        menu.add(windowSizeItem);
        MenuItem settings = new MenuItem("Settings...");
        settings.addActionListener(event -> SwingUtilities.invokeLater(this::showSettingsDialog));
        menu.add(settings);
        menu.addSeparator();
        MenuItem quit = new MenuItem("Quit nashclip");
        quit.addActionListener(event -> quit());
        menu.add(quit);
        TrayIcon icon = new TrayIcon(createTrayImage(pinned),
                pinned ? "nashclip — pinned open" : "nashclip", menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(event -> SwingUtilities.invokeLater(() -> showWindow(frame)));
        try {
            SystemTray.getSystemTray().add(icon);
            trayIcon = icon;
            return true;
        } catch (AWTException | SecurityException error) {
            System.err.println("Menu bar unavailable; use the edge arrow to reopen: "
                    + error.getMessage());
            return false;
        }
    }

    private BufferedImage createTrayImage(boolean selected) {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D drawing = image.createGraphics();
        ClipboardTheme.ClipboardMark mark = new ClipboardTheme.ClipboardMark(32);
        mark.setSize(32, 32);
        mark.paint(drawing);
        if (selected) {
            drawing.setColor(ClipboardTheme.ACCENT_SURFACE);
            drawing.fillOval(20, 19, 11, 11);
            drawing.setColor(ClipboardTheme.ACCENT);
            drawing.drawOval(20, 19, 10, 10);
            drawing.fillRoundRect(24, 21, 3, 4, 1, 1);
            drawing.drawLine(25, 25, 25, 28);
        }
        drawing.dispose();
        return image;
    }

    private void showWindow(Window window) {
        if (stopped) return;
        if (edgeDrawer != null) {
            edgeDrawer.showDrawer();
            return;
        }
        if (window instanceof JFrame frame) {
            frame.setState(JFrame.NORMAL);
        }
        window.setVisible(true);
    }

    private void syncPinnedUI(boolean selected) {
        pinned = selected;
        if (pinButton != null) {
            pinButton.setSelected(selected);
            updatePinButton(pinButton);
        }
        if (trayPinItem != null) {
            trayPinItem.setState(selected);
        }
        if (trayIcon != null) {
            trayIcon.setImage(createTrayImage(selected));
            trayIcon.setToolTip(selected ? "nashclip — pinned open" : "nashclip");
        }
        if (edgeDrawer != null) {
            edgeDrawer.setPinned(selected);
        }
    }

    private void applyPin(boolean selected) {
        syncPinnedUI(selected);
        try {
            AppSettings.savePinned(settingsDirectory, selected);
            statusLabel.setText(selected ? "Pinned open. Use the arrow to close."
                    : "Unpinned. Outside click closes until you click inside.");
            statusLabel.setToolTipText(null);
        } catch (IOException error) {
            statusLabel.setText("Pin changed for this session; settings could not be saved.");
            statusLabel.setToolTipText(error.getMessage());
            System.err.println("Could not save pin setting: " + error.getMessage());
        }
    }

    private void applyTheme(String selected) {
        if (theme.equals(selected)) return;
        try {
            AppSettings current = AppSettings.load(settingsDirectory);
            new AppSettings(current.historyLimit(), current.pinned(), selected, current.sizeNoticeSeconds()).save(settingsDirectory);
            setThemeView(selected);
            statusLabel.setText(selected.equals("dark") ? "Dark theme on." : "Light theme on.");
        } catch (IOException error) {
            if (trayDarkItem != null) trayDarkItem.setState(theme.equals("dark"));
            statusLabel.setText("Could not save theme: " + error.getMessage());
        }
    }

    private void showSettingsDialog() {
        if (edgeDrawer != null) {
            edgeDrawer.setModalInteraction(true);
            showWindow(SwingUtilities.getWindowAncestor(pinButton));
        }
        try {
            editSettings();
        } finally {
            if (edgeDrawer != null) {
                Timer resumeDrawer = new Timer(200,
                        event -> edgeDrawer.setModalInteraction(false));
                resumeDrawer.setRepeats(false);
                resumeDrawer.start();
            }
        }
    }

    private void showSizeDialog() {
        edgeDrawer.setModalInteraction(true);
        showWindow(frame);
        try {
            JSpinner width = new JSpinner(new SpinnerNumberModel(windowSize.width(),
                    WindowSizeStore.MIN_WIDTH, WindowSizeStore.MAX_WIDTH, 20));
            JSpinner height = new JSpinner(new SpinnerNumberModel(windowSize.height(),
                    WindowSizeStore.MIN_HEIGHT, WindowSizeStore.MAX_HEIGHT, 20));
            JPanel fields = new JPanel(new GridLayout(0, 2, 8, 8));
            fields.add(new JLabel("Width (px):"));
            fields.add(width);
            fields.add(new JLabel("Height (px):"));
            fields.add(height);
            Object[] actions = {"Apply size", "Save as default", "Reset to default", "Cancel"};
            int choice = JOptionPane.showOptionDialog(pinButton, fields, "nashclip Window Size",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, actions, actions[0]);
            if (choice < 0 || choice == 3) return;
            try {
                WindowSizeStore.Size next;
                if (choice == 2) {
                    next = windowSize.resetToDefault();
                } else {
                    width.commitEdit();
                    height.commitEdit();
                    next = windowSize.withCurrent(((Number) width.getValue()).intValue(),
                            ((Number) height.getValue()).intValue());
                    if (choice == 1) next = next.saveCurrentAsDefault();
                }
                sizeStore.save(next);
                windowSize = next;
                edgeDrawer.resize(next.width(), next.height());
                historyGrid.setAvailableWidth(edgeDrawer.panelWidth());
                statusLabel.setText(choice == 1 ? "Default window size saved."
                        : choice == 2 ? "Window size reset to default." : "Window size saved.");
            } catch (IOException | java.text.ParseException | IllegalArgumentException error) {
                JOptionPane.showMessageDialog(pinButton, "Could not change window size: "
                        + error.getMessage(), "Window Size", JOptionPane.ERROR_MESSAGE);
            }
        } finally {
            Timer resumeDrawer = new Timer(200, event -> edgeDrawer.setModalInteraction(false));
            resumeDrawer.setRepeats(false);
            resumeDrawer.start();
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

    private void editSettings() {
        JSpinner limitInput = new JSpinner(new SpinnerNumberModel(historyLimit, 1,
                HistoryPolicy.MAX_ENTRIES, 1));
        JCheckBox pinnedInput = new JCheckBox("Keep drawer open", pinned);
        JCheckBox darkInput = new JCheckBox("Dark mode", theme.equals("dark"));
        JPanel options = new JPanel(new GridLayout(0, 1, 0, 8));
        options.add(new JLabel("Maximum clips in history:"));
        options.add(limitInput);
        options.add(pinnedInput);
        options.add(darkInput);
        options.add(new JLabel("A lower limit removes the oldest clips."));
        int choice = JOptionPane.showConfirmDialog(pinButton, options, "nashclip Settings",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        int newLimit;
        try {
            limitInput.commitEdit();
            newLimit = ((Number) limitInput.getValue()).intValue();
        } catch (java.text.ParseException | ClassCastException error) {
            JOptionPane.showMessageDialog(pinButton, "Enter a positive whole number for the history limit.",
                    "Invalid setting", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            if (newLimit < 1 || newLimit > HistoryPolicy.MAX_ENTRIES) throw new IOException("History limit must be 1–500");
            saveSettings(newLimit, pinnedInput.isSelected(), darkInput.isSelected() ? "dark" : "light");
        } catch (IOException error) {
            JOptionPane.showMessageDialog(pinButton, "Could not save settings: " + error.getMessage(),
                    "nashclip Settings", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveSettings(int newLimit, boolean newPinned, String newTheme) throws IOException {
        AppSettings current = AppSettings.load(settingsDirectory);
        sizeNoticeSeconds = current.sizeNoticeSeconds();
        new AppSettings(newLimit, newPinned, newTheme, sizeNoticeSeconds).save(settingsDirectory);
        settingsLoadFailed = false;
        updateHistoryLimit(newLimit);
        syncPinnedUI(newPinned);
        if (!theme.equals(newTheme)) {
            setThemeView(newTheme);
        }
        statusLabel.setText(persistHistory(historyModel) ? "Settings saved."
                : "Settings saved; history could not be saved.");
        statusLabel.setToolTipText(null);
    }

    private void setThemeView(String value) {
        theme = value;
        ClipboardTheme.use(value);
        if (trayDarkItem != null) trayDarkItem.setState(value.equals("dark"));
        String shortcut = shortcutLabel.getText();
        String shortcutTip = shortcutLabel.getToolTipText();
        frame.setContentPane(createContent(historyModel, historyGrid));
        shortcutLabel.setText(shortcut);
        shortcutLabel.setToolTipText(shortcutTip);
        historyGrid.refresh();
        edgeDrawer.installHeaderDrag(dragHeader);
        edgeDrawer.refreshTheme();
        frame.revalidate();
        frame.repaint();
        if (trayIcon != null) trayIcon.setImage(createTrayImage(pinned));
    }

    private void updateHistoryLimit(int newLimit) {
        historyLimit = newLimit;
        historyStore = new HistoryStore(settingsDirectory, newLimit);
        if (historyGrid != null && historyModel != null) {
            refreshGrid();
        }
        if (limitLabel != null) {
            limitLabel.setText((settingsLoadFailed ? "Saving paused" : "Local text history")
                    + "  ·  " + newLimit + " clip limit");
        }
    }

    JPanel createContent(DefaultListModel<String> historyModel, JPanel grid) {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(ClipboardTheme.BACKGROUND);
        root.setBorder(new EmptyBorder(12, 14, 10, 14));

        JPanel brand = ClipboardTheme.transparentPanel(new BorderLayout(10, 0));
        dragHeader = brand;
        brand.add(new ClipboardTheme.ClipboardMark(34), BorderLayout.WEST);
        JPanel titles = ClipboardTheme.transparentPanel(new BorderLayout(0, 2));
        titles.add(ClipboardTheme.label("nashclip", Font.BOLD, 18,
                ClipboardTheme.TEXT), BorderLayout.NORTH);
        countLabel = ClipboardTheme.label(historyModel.size()
                + (historyModel.size() == 1 ? " clip" : " clips") + " · " + pinnedClips.size() + "/5 pinned",
                Font.PLAIN, 11, ClipboardTheme.MUTED);
        titles.add(countLabel, BorderLayout.SOUTH);
        brand.add(titles, BorderLayout.CENTER);
        JButton live = new JButton(clipboardWatcher.isPaused() ? "Ⅱ Paused" : "● Live");
        live.setFont(ClipboardTheme.font(Font.PLAIN, 11));
        live.setForeground(ClipboardTheme.GREEN);
        live.setBorder(BorderFactory.createEmptyBorder());
        live.setContentAreaFilled(false);
        live.setFocusable(false);
        live.addActionListener(event -> {
            clipboardWatcher.setPaused(!clipboardWatcher.isPaused());
            live.setText(clipboardWatcher.isPaused() ? "Ⅱ Paused" : "● Live");
            statusLabel.setText(clipboardWatcher.isPaused() ? "Capture paused until resumed or restarted."
                    : "Capture resumed. Text copied while paused is skipped.");
        });
        live.setToolTipText("Pause or resume clipboard capture for this session");
        JPanel indicators = ClipboardTheme.transparentPanel(new BorderLayout(0, 4));
        live.setHorizontalAlignment(JLabel.RIGHT);
        indicators.add(live, BorderLayout.NORTH);
        shortcutLabel = ClipboardTheme.label("", Font.PLAIN, 11, ClipboardTheme.MUTED);
        shortcutLabel.setHorizontalAlignment(JLabel.RIGHT);
        indicators.add(shortcutLabel, BorderLayout.SOUTH);
        pinButton = new ClipboardTheme.PinButton();
        pinButton.setSelected(pinned);
        updatePinButton(pinButton);
        pinButton.addActionListener(event -> applyPin(pinButton.isSelected()));
        JButton themeButton = new ClipboardTheme.ActionButton(theme.equals("dark") ? "☀" : "☾", false);
        themeButton.setToolTipText(theme.equals("dark") ? "Switch to light mode" : "Switch to dark mode");
        themeButton.getAccessibleContext().setAccessibleName(themeButton.getToolTipText());
        themeButton.addActionListener(event -> applyTheme(theme.equals("dark") ? "light" : "dark"));
        JButton sizeButton = new ClipboardTheme.ActionButton("⤢", false);
        sizeButton.setToolTipText("Change window size");
        sizeButton.getAccessibleContext().setAccessibleName("Change window size");
        sizeButton.addActionListener(event -> showSizeDialog());
        JButton hideButton = windowButton("−", "Hide to edge (keeps capturing)",
                () -> edgeDrawer.hideDrawer());
        JButton quitButton = windowButton("×", "Quit nashclip (stops capturing)", this::quit);
        JPanel controls = ClipboardTheme.transparentPanel(new BorderLayout(8, 0));
        JPanel switches = ClipboardTheme.transparentPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        switches.add(sizeButton);
        switches.add(themeButton);
        switches.add(pinButton);
        switches.add(hideButton);
        switches.add(quitButton);
        controls.add(switches, BorderLayout.WEST);
        controls.add(indicators, BorderLayout.EAST);
        brand.add(controls, BorderLayout.EAST);
        root.add(brand, BorderLayout.NORTH);

        JPanel canvas = ClipboardTheme.transparentPanel(new BorderLayout());
        canvas.add(grid, BorderLayout.NORTH);
        JScrollPane scrollPane = new JScrollPane(canvas,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setBackground(ClipboardTheme.BACKGROUND);
        ClipboardTheme.styleScrollBar(scrollPane.getVerticalScrollBar());
        root.add(scrollPane, BorderLayout.CENTER);

        JPanel footer = ClipboardTheme.transparentPanel(new BorderLayout(16, 0));
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, ClipboardTheme.BORDER),
                new EmptyBorder(8, 0, 0, 0)));
        statusLabel = ClipboardTheme.label(settingsLoadFailed
                ? "Settings unavailable: using 40 clips. History saving paused."
                : pinned ? "Pinned open. Use the arrow to close."
                : "Use pastes and hides this window.",
                Font.PLAIN, 11, ClipboardTheme.MUTED);
        if (settingsLoadFailed) {
            statusLabel.setToolTipText("Fix ~/.nashclip/settings.json and restart to resume saving history.");
        }
        footer.add(statusLabel, BorderLayout.CENTER);
        limitLabel = ClipboardTheme.label((settingsLoadFailed ? "Saving paused" : "Local text history")
                + "  ·  " + historyLimit + " clip limit",
                Font.PLAIN, 11, ClipboardTheme.MUTED);
        footer.add(limitLabel, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        JPanel surround = new JPanel(new BorderLayout());
        surround.setBackground(ClipboardTheme.BACKGROUND);
        surround.add(root, BorderLayout.CENTER);
        surround.add(resizeGrip(EdgeDrawer.ResizeEdge.LEFT), BorderLayout.WEST);
        surround.add(resizeGrip(EdgeDrawer.ResizeEdge.RIGHT), BorderLayout.EAST);
        surround.add(resizeGrip(EdgeDrawer.ResizeEdge.TOP), BorderLayout.NORTH);
        surround.add(resizeGrip(EdgeDrawer.ResizeEdge.BOTTOM), BorderLayout.SOUTH);
        return surround;
    }

    private JPanel resizeGrip(EdgeDrawer.ResizeEdge edge) {
        boolean horizontal = edge == EdgeDrawer.ResizeEdge.LEFT
                || edge == EdgeDrawer.ResizeEdge.RIGHT;
        JPanel grip = new JPanel();
        grip.setOpaque(false);
        grip.setPreferredSize(horizontal ? new Dimension(7, 1) : new Dimension(1, 7));
        grip.setToolTipText("Drag to resize window");
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                boolean enabled = edgeDrawer != null && edgeDrawer.canResizeFrom(edge);
                int cursor = switch (edge) {
                    case LEFT -> Cursor.W_RESIZE_CURSOR;
                    case RIGHT -> Cursor.E_RESIZE_CURSOR;
                    case TOP -> Cursor.N_RESIZE_CURSOR;
                    case BOTTOM -> Cursor.S_RESIZE_CURSOR;
                };
                grip.setCursor(Cursor.getPredefinedCursor(enabled ? cursor : Cursor.DEFAULT_CURSOR));
            }

            @Override
            public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event) && edgeDrawer != null) {
                    edgeDrawer.beginResize(edge, event.getLocationOnScreen());
                }
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (edgeDrawer != null) edgeDrawer.dragResize(event.getLocationOnScreen());
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (edgeDrawer != null) edgeDrawer.endResize(event.getLocationOnScreen());
            }
        };
        grip.addMouseListener(mouse);
        grip.addMouseMotionListener(mouse);
        return grip;
    }

    private void updatePinButton(ClipboardTheme.PinButton button) {
        String description = button.isSelected() ? "Unpin nashclip" : "Pin nashclip open";
        button.setToolTipText(description);
        button.getAccessibleContext().setAccessibleName(description);
    }
    private void finishPaste(Window window, PasteService.Result result) {
        statusLabel.setText(result.message());
        statusLabel.setToolTipText(result.message());
        if (window != null && !result.sent()) {
            showWindow(window);
        } else if (result.sent() && edgeDrawer != null && edgeDrawer.isPinned()) {
            edgeDrawer.showDrawer();
        } else if (edgeDrawer == null && window instanceof JFrame frame && !trayAvailable) {
            // Keep a Dock entry available if menu bar integration failed.
            frame.setState(JFrame.ICONIFIED);
            frame.setVisible(true);
        }
    }

    private JPanel createTile(String text, int index, Runnable onDelete) {
        JPanel tile = new ClipboardTheme.Card(new BorderLayout(0, 8));
        JPanel tileHeader = ClipboardTheme.transparentPanel(new BorderLayout());
        JLabel type = ClipboardTheme.label(pinnedClips.contains(text) ? "PINNED" : "≡  TEXT", Font.BOLD, 11, ClipboardTheme.ACCENT);
        tileHeader.add(type, BorderLayout.WEST);
        JPanel trailing = ClipboardTheme.transparentPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        trailing.add(ClipboardTheme.label(String.format("%02d", index + 1), Font.PLAIN, 11,
                ClipboardTheme.MUTED));
        ClipboardTheme.PinButton clipPin = new ClipboardTheme.PinButton();
        clipPin.setSelected(pinnedClips.contains(text));
        clipPin.setToolTipText(clipPin.isSelected() ? "Unpin this clip" : "Pin this clip (up to 5)");
        clipPin.getAccessibleContext().setAccessibleName(clipPin.getToolTipText());
        clipPin.addActionListener(event -> toggleClipPin(text));
        trailing.add(clipPin);
        JButton deleteButton = new ClipboardTheme.ActionButton("", true);
        deleteButton.setToolTipText("Delete this clip");
        deleteButton.getAccessibleContext().setAccessibleName("Delete clip " + (index + 1));
        deleteButton.addActionListener(event -> onDelete.run());
        trailing.add(deleteButton);
        tileHeader.add(trailing, BorderLayout.EAST);
        tile.add(tileHeader, BorderLayout.NORTH);

        int textWidth = TILE_WIDTH - 24;
        JTextArea content = new JTextArea(HistoryPolicy.preview(text));
        content.setEditable(false);
        content.setFocusable(false);
        content.setOpaque(false);
        content.setForeground(ClipboardTheme.TEXT);
        content.setFont(ClipboardTheme.font(Font.PLAIN, 13));
        content.setLineWrap(true);
        content.setWrapStyleWord(true);
        content.setBorder(BorderFactory.createEmptyBorder());
        content.setSize(textWidth, Short.MAX_VALUE);
        int textHeight = Math.max(48, content.getPreferredSize().height);
        content.setPreferredSize(new Dimension(textWidth, textHeight));
        tile.add(content, BorderLayout.CENTER);

        JButton useButton = new ClipboardTheme.ActionButton("Use  ↗", false);
        useButton.setToolTipText("Paste into your previous app and hide nashclip");
        useButton.getAccessibleContext().setAccessibleName("Use clip " + (index + 1));
        useButton.addActionListener(event -> useClip(text));
        JPanel tileFooter = ClipboardTheme.transparentPanel(new BorderLayout());
        int characters = text.codePointCount(0, text.length());
        tileFooter.add(ClipboardTheme.label(characters + (characters == 1 ? " character" : " characters"),
                Font.PLAIN, 11, ClipboardTheme.MUTED), BorderLayout.WEST);
        tileFooter.add(useButton, BorderLayout.EAST);
        tile.add(tileFooter, BorderLayout.SOUTH);
        tile.setPreferredSize(new Dimension(TILE_WIDTH, tile.getPreferredSize().height));
        tile.setMaximumSize(tile.getPreferredSize());
        tile.setAlignmentX(Component.LEFT_ALIGNMENT);
        return tile;
    }
    private JButton windowButton(String label, String description, Runnable action) {
        JButton button = new ClipboardTheme.ActionButton(label, false);
        button.setPreferredSize(new Dimension(28, 28));
        button.setBorder(new EmptyBorder(0, 0, 0, 0));
        button.setToolTipText(description);
        button.getAccessibleContext().setAccessibleName(description);
        button.addActionListener(event -> action.run());
        return button;
    }

    private void useClip(String text) {
        if (stopped || useInProgress) return;
        try {
            clipboardWatcher.copy(text);
        } catch (IllegalStateException | SecurityException error) {
            statusLabel.setText("Clipboard unavailable. Try Use again.");
            return;
        }
        useInProgress = true;
        statusLabel.setText("Copied; restoring previous app...");
        pasteWorker = new Thread(() -> {
            pasteService.rememberFrontmostApplication();
            PasteService.Target target = pasteService.target();
            try {
                SwingUtilities.invokeAndWait(() -> {
                    if (!stopped) edgeDrawer.hideImmediately();
                });
                if (Thread.currentThread().isInterrupted()) { pasteService.cancel(target); return; }
                PasteService.Result result = pasteService.paste(target, text);
                SwingUtilities.invokeLater(() -> {
                    useInProgress = false;
                    if (!stopped) finishPaste(frame, result);
                });
            } catch (InterruptedException error) {
                pasteService.cancel(target);
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                pasteService.cancel(target);
                SwingUtilities.invokeLater(() -> {
                    useInProgress = false;
                    if (!stopped) finishPaste(frame,
                            new PasteService.Result(false, "Copied, but paste failed."));
                });
            }
        }, "nashclip-paste");
        pasteWorker.setDaemon(true);
        pasteWorker.start();
    }

    private void quit() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::quit);
            return;
        }
        if (stopped) return;
        stopped = true;
        if (shutdownHook != null) {
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch (IllegalStateException shuttingDown) { }
        }
        if (clipboardWatcher != null) clipboardWatcher.stop();
        if (sizeNotice != null) sizeNotice.close();
        if (pasteWorker != null) pasteWorker.interrupt();
        globalHotkey.close();
        pasteService.close();
        if (trayIcon != null) SystemTray.getSystemTray().remove(trayIcon);
        if (edgeDrawer != null) edgeDrawer.close();
        historyModel.clear();
        try { instanceLock.close(); } catch (IOException error) { System.err.println(error.getMessage()); }
        System.exit(0);
    }

    private List<String> historyEntries() {
        return new ArrayList<>(java.util.Collections.list(historyModel.elements()));
    }

    private void toggleClipPin(String text) {
        List<String> next = new ArrayList<>(pinnedClips);
        boolean removing = next.remove(text);
        if (!removing) {
            if (next.size() == HistoryPolicy.MAX_PINNED) {
                statusLabel.setText("Only 5 clips can be pinned. Unpin one first.");
                historyGrid.refresh();
                return;
            }
            next.add(0, text);
        }
        if (commitHistory(historyEntries(), next))
            statusLabel.setText(removing ? "Clip unpinned." : "Clip pinned · " + next.size() + "/5");
        else historyGrid.refresh();
    }

    private boolean commitHistory(List<String> entries, List<String> pins) {
        if (settingsLoadFailed || historyLoadFailed) {
            statusLabel.setText("Cannot save clips until the data-file error is fixed.");
            return false;
        }
        List<String> bounded = HistoryPolicy.withPins(entries, pins, historyLimit);
        try {
            historyStore.saveSnapshot(new HistoryStore.Snapshot(bounded, pins));
            pinnedClips.clear();
            pinnedClips.addAll(pins);
            historyModel.clear();
            bounded.forEach(historyModel::addElement);
            refreshGrid();
            return true;
        } catch (IOException error) {
            statusLabel.setText("Could not save clip changes. Nothing was changed.");
            return false;
        }
    }

    private JPanel createHistoryTile(int i) {
        String text = historyModel.get(i);
        return createTile(text, i, () -> {
            List<String> next = historyEntries();
            next.remove(i);
            List<String> pins = new ArrayList<>(pinnedClips);
            pins.remove(text);
            commitHistory(next, pins);
        });
    }
    private DefaultListModel<String> loadHistoryModel() {
        DefaultListModel<String> historyModel = new DefaultListModel<>();
        try {
            HistoryStore.Snapshot saved = historyStore.loadSnapshot();
            pinnedClips.addAll(saved.pinned());
            for (String entry : saved.entries()) {
                historyModel.addElement(entry);
            }
        } catch (IOException error) {
            historyLoadFailed = true;
            System.err.println("Could not load clipboard history: " + error.getMessage());
        }
        return historyModel;
    }

    private boolean persistHistory(DefaultListModel<String> historyModel) {
        if (historyLoadFailed || settingsLoadFailed) {
            return false;
        }
        List<String> entries = new ArrayList<>();
        for (int index = 0; index < historyModel.size(); index++) {
            entries.add(historyModel.get(index));
        }
        try {
            historyStore.saveSnapshot(new HistoryStore.Snapshot(entries, pinnedClips));
            return true;
        } catch (IOException error) {
            if (statusLabel != null) statusLabel.setText("Could not save history. Changes are only in memory.");
            System.err.println("Could not save clipboard history: " + error.getMessage());
            return false;
        }
    }
    private JFrame createJframe() {
        JFrame frame = new JFrame("nashclip");
        frame.setUndecorated(true);
        frame.setSize(windowSize.width(), windowSize.height());
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) { quit(); }
        });
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        frame.setLocation(screen.x + Math.max(0, (screen.width - frame.getWidth()) / 2),
                screen.y + Math.max(0, screen.height - frame.getHeight() - 12));
        return frame;
    }
}
