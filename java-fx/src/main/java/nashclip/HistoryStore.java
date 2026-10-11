package nashclip;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

final class HistoryStore {
    private static final String FILE_NAME = "history.properties";

    private final Path directory;
    private final Path file;
    private final int maxEntries;

    HistoryStore(Path directory, int maxEntries) {
        if (maxEntries < 1 || maxEntries > HistoryPolicy.MAX_ENTRIES) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.directory = directory;
        this.file = directory.resolve(FILE_NAME);
        this.maxEntries = maxEntries;
    }

    record Snapshot(List<String> entries, List<String> pinned) {
        Snapshot { entries = List.copyOf(entries); pinned = List.copyOf(pinned); }
    }

    List<String> load() throws IOException { return loadSnapshot().entries(); }

    Snapshot loadSnapshot() throws IOException {
        ensureDirectory();
        if (Files.isSymbolicLink(file)) {
            throw new IOException("History file must not be a symbolic link: " + file);
        }
        if (!Files.exists(file)) {
            return new Snapshot(List.of(), List.of());
        }

        Properties properties = new Properties();
        try (Reader reader = new java.io.StringReader(new String(
                HistoryPolicy.read(file, HistoryPolicy.MAX_FILE_BYTES), StandardCharsets.UTF_8))) {
            properties.load(reader);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid history file: " + file, error);
        }

        String version = properties.getProperty("version");
        if (!"1".equals(version) && !"2".equals(version)) {
            throw new IOException("Unsupported history file version: " + file);
        }

        int count;
        try {
            count = Integer.parseInt(properties.getProperty("count", ""));
        } catch (NumberFormatException error) {
            throw new IOException("Invalid history entry count: " + file, error);
        }
        if (count < 0) {
            throw new IOException("Invalid history entry count: " + file);
        }

        List<String> pinned = new ArrayList<>();
        if ("2".equals(version)) {
            int pinCount;
            try { pinCount = Integer.parseInt(properties.getProperty("pinned.count", "")); }
            catch (NumberFormatException error) { throw new IOException("Invalid pinned count", error); }
            if (pinCount < 0 || pinCount > HistoryPolicy.MAX_PINNED) throw new IOException("Invalid pinned count");
            for (int i = 0; i < pinCount; i++) {
                String text = properties.getProperty("pinned." + i);
                if (!HistoryPolicy.accepts(text) || pinned.contains(text)) throw new IOException("Invalid pinned clip");
                pinned.add(text);
            }
        }
        List<String> entries = new ArrayList<>();
        for (int index = 0; index < Math.min(count, maxEntries); index++) {
            String entry = properties.getProperty("entry." + index);
            if (entry == null) {
                throw new IOException("Missing history entry " + index + ": " + file);
            }
            if (entry.length() > HistoryPolicy.MAX_TEXT_UNITS)
                throw new IOException("Saved clip exceeds safety limit; original file preserved");
            entries.add(entry);
        }
        return new Snapshot(HistoryPolicy.withPins(entries, pinned, maxEntries), pinned);
    }

    void save(List<String> entries) throws IOException { saveSnapshot(new Snapshot(entries, List.of())); }

    void saveSnapshot(Snapshot snapshot) throws IOException {
        List<String> pinned = snapshot.pinned();
        List<String> entries = HistoryPolicy.withPins(snapshot.entries(), pinned, maxEntries)
                .stream().filter(text -> !pinned.contains(text)).toList();
        ensureDirectory();
        if (Files.isSymbolicLink(file)) {
            throw new IOException("History file must not be a symbolic link: " + file);
        }

        Properties properties = new Properties();
        int count = Math.min(entries.size(), maxEntries);
        properties.setProperty("version", "2");
        properties.setProperty("pinned.count", Integer.toString(pinned.size()));
        for (int i = 0; i < pinned.size(); i++) properties.setProperty("pinned." + i, pinned.get(i));
        properties.setProperty("count", Integer.toString(count));
        for (int index = 0; index < count; index++) {
            properties.setProperty("entry." + index, entries.get(index));
        }

        Path temporaryFile = Files.createTempFile(directory, "history-", ".tmp",
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")));
        try {
            try (Writer writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8)) {
                properties.store(writer, "nashclip clipboard history");
            }
            try {
                Files.move(temporaryFile, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private void ensureDirectory() throws IOException {
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("History directory must not be a symbolic link: " + directory);
        }
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }
}
