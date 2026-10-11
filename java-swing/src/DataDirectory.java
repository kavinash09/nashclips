import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** Copies legacy clipboard data once, leaving the original folder untouched. */
final class DataDirectory {
    private static final String[] FILES = {
            "settings.json", "history.properties", "window-size.properties",
            "java-fx-position.properties", "swift-position.json"
    };

    static Path prepare() {
        Path home = Path.of(System.getProperty("user.home"));
        Path current = home.resolve(".nashclip");
        Path legacy = home.resolve(".mcopier");
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(legacy, LinkOption.NOFOLLOW_LINKS)) return current;
        try {
            migrate(home, legacy, current);
            return current;
        } catch (IOException error) {
            System.err.println("Could not migrate clipboard data: " + error.getMessage());
            return Files.exists(current, LinkOption.NOFOLLOW_LINKS) ? current : legacy;
        }
    }

    private static void migrate(Path home, Path legacy, Path current) throws IOException {
        Path staging = Files.createTempDirectory(home, ".nashclip-migration-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        try {
            for (String name : FILES) {
                Path source = legacy.resolve(name);
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) continue;
                Path destination = staging.resolve(name);
                Files.copy(source, destination, LinkOption.NOFOLLOW_LINKS);
                Files.setPosixFilePermissions(destination,
                        PosixFilePermissions.fromString("rw-------"));
            }
            try {
                Files.move(staging, current);
            } catch (FileAlreadyExistsException error) {
                // Another instance completed the migration first.
            }
        } finally {
            for (String name : FILES) Files.deleteIfExists(staging.resolve(name));
            Files.deleteIfExists(staging);
        }
    }

    private DataDirectory() { }
}
