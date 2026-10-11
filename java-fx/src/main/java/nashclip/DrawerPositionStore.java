package nashclip;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Properties;

/** Saves the JavaFX drawer's screen, side, and relative vertical position. */
final class DrawerPositionStore {
    private static final String FILE_NAME = "java-fx-position.properties";
    private static final String PRIVATE_FILE = "rw-------";
    private static final String PRIVATE_DIRECTORY = "rwx------";

    record Position(boolean rightSide, double screenX, double screenY,
            double screenWidth, double screenHeight, double verticalFraction) {
        Position {
            if (!Double.isFinite(screenX) || !Double.isFinite(screenY)
                    || !Double.isFinite(screenWidth) || !Double.isFinite(screenHeight)
                    || !Double.isFinite(verticalFraction) || screenWidth <= 0 || screenHeight <= 0
                    || verticalFraction < 0 || verticalFraction > 1) {
                throw new IllegalArgumentException("Invalid drawer position");
            }
        }
    }

    private final Path directory;
    private final Path file;

    DrawerPositionStore(Path directory) {
        this.directory = directory;
        this.file = directory.resolve(FILE_NAME);
    }

    Optional<Position> load() throws IOException {
        if (Files.isSymbolicLink(directory)) throw new IOException("Data directory is a symbolic link");
        if (Files.isSymbolicLink(file)) throw new IOException("Position file must not be a symbolic link");
        if (!Files.exists(file)) return Optional.empty();
        Properties values = new Properties();
        try (Reader reader = new java.io.StringReader(new String(
                HistoryPolicy.read(file, 65_536), StandardCharsets.UTF_8))) {
            values.load(reader);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid drawer position file", error);
        }
        try {
            String side = values.getProperty("side");
            if (!"left".equals(side) && !"right".equals(side)) {
                throw new IllegalArgumentException("side must be left or right");
            }
            return Optional.of(new Position("right".equals(side),
                    Double.parseDouble(values.getProperty("screenX")),
                    Double.parseDouble(values.getProperty("screenY")),
                    Double.parseDouble(values.getProperty("screenWidth")),
                    Double.parseDouble(values.getProperty("screenHeight")),
                    Double.parseDouble(values.getProperty("verticalFraction"))));
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IOException("Invalid drawer position file", error);
        }
    }

    void save(Position position) throws IOException {
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("Settings directory must not be a symbolic link");
        }
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString(PRIVATE_DIRECTORY)));
        if (Files.isSymbolicLink(file)) throw new IOException("Position file must not be a symbolic link");
        String content = "side=" + (position.rightSide() ? "right" : "left") + "\n"
                + "screenX=" + position.screenX() + "\n"
                + "screenY=" + position.screenY() + "\n"
                + "screenWidth=" + position.screenWidth() + "\n"
                + "screenHeight=" + position.screenHeight() + "\n"
                + "verticalFraction=" + position.verticalFraction() + "\n";
        Path temporary = Files.createTempFile(directory, "drawer-position-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(PRIVATE_FILE)));
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
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
}
