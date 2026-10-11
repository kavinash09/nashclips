package nashclip;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;

/** Shared size preference for the Swing, JavaFX, and Swift drawers. */
final class WindowSizeStore {
    static final int FACTORY_WIDTH = 760;
    static final int FACTORY_HEIGHT = 340;
    static final int MIN_WIDTH = 500;
    static final int MIN_HEIGHT = 260;
    static final int MAX_WIDTH = 4096;
    static final int MAX_HEIGHT = 2160;

    record Size(int width, int height, int defaultWidth, int defaultHeight) {
        Size {
            if (!valid(width, MIN_WIDTH, MAX_WIDTH) || !valid(height, MIN_HEIGHT, MAX_HEIGHT)
                    || !valid(defaultWidth, MIN_WIDTH, MAX_WIDTH)
                    || !valid(defaultHeight, MIN_HEIGHT, MAX_HEIGHT)) {
                throw new IllegalArgumentException("Invalid window size");
            }
        }

        Size withCurrent(int width, int height) {
            return new Size(width, height, defaultWidth, defaultHeight);
        }

        Size saveCurrentAsDefault() {
            return new Size(width, height, width, height);
        }

        Size resetToDefault() {
            return new Size(defaultWidth, defaultHeight, defaultWidth, defaultHeight);
        }
    }

    private final Path directory;
    private final Path file;

    WindowSizeStore(Path directory) {
        this.directory = directory;
        file = directory.resolve("window-size.properties");
    }

    Size load() throws IOException {
        if (Files.isSymbolicLink(directory)) throw new IOException("Data directory is a symbolic link");
        if (Files.isSymbolicLink(file)) throw new IOException("Window size file must not be a symbolic link");
        if (!Files.exists(file)) return factory();
        Properties properties = new Properties();
        try (Reader reader = new java.io.StringReader(new String(
                HistoryPolicy.read(file, 65_536), StandardCharsets.UTF_8))) {
            properties.load(reader);
            return new Size(
                    Integer.parseInt(properties.getProperty("width")),
                    Integer.parseInt(properties.getProperty("height")),
                    Integer.parseInt(properties.getProperty("defaultWidth")),
                    Integer.parseInt(properties.getProperty("defaultHeight")));
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IOException("Invalid window size file", error);
        }
    }

    void save(Size size) throws IOException {
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(file)) {
            throw new IOException("Window size path must not be a symbolic link");
        }
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
        Path temporary = Files.createTempFile(directory, "window-size-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        String value = "width=" + size.width() + "\nheight=" + size.height()
                + "\ndefaultWidth=" + size.defaultWidth()
                + "\ndefaultHeight=" + size.defaultHeight() + "\n";
        try {
            Files.writeString(temporary, value, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static Size factory() {
        return new Size(FACTORY_WIDTH, FACTORY_HEIGHT, FACTORY_WIDTH, FACTORY_HEIGHT);
    }

    private static boolean valid(int value, int minimum, int maximum) {
        return value >= minimum && value <= maximum;
    }
}
