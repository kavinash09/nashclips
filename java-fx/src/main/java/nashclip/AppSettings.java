package nashclip;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared settings in ~/.nashclip/settings.json. */
record AppSettings(int historyLimit, boolean pinned, String theme, int sizeNoticeSeconds) {
    static final int DEFAULT_HISTORY_LIMIT = 40;
    private static final String SPACE = "[ \\t\\r\\n]*";
    private static final Pattern FIELD = Pattern.compile(SPACE
            + "\"(historyLimit|pinned|theme|sizeNoticeSeconds)\"" + SPACE + ":" + SPACE
            + "(0|[1-9][0-9]*|true|false|\"light\"|\"dark\")" + SPACE);

    AppSettings {
        if (sizeNoticeSeconds != 0 && (sizeNoticeSeconds < 2 || sizeNoticeSeconds > 5))
            throw new IllegalArgumentException("sizeNoticeSeconds must be 0 or 2–5");
        if (historyLimit < 1 || historyLimit > HistoryPolicy.MAX_ENTRIES) {
            throw new IllegalArgumentException("historyLimit must be between 1 and 500");
        }
        if (!theme.equals("light") && !theme.equals("dark")) {
            throw new IllegalArgumentException("theme must be light or dark");
        }
    }

    AppSettings(int historyLimit, boolean pinned, String theme) {
        this(historyLimit, pinned, theme, 4);
    }

    AppSettings(int historyLimit) {
        this(historyLimit, false, "light");
    }

    AppSettings(int historyLimit, boolean pinned) {
        this(historyLimit, pinned, "light");
    }

    static AppSettings load(Path directory) throws IOException {
        ensureDirectory(directory);
        Path file = directory.resolve("settings.json");
        if (Files.isSymbolicLink(file)) {
            throw new IOException("Settings file must not be a symbolic link");
        }
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
            Files.writeString(file, new AppSettings(DEFAULT_HISTORY_LIMIT, false, "light").toJson(),
                    StandardCharsets.UTF_8);
        } catch (FileAlreadyExistsException existing) {
            // Preserve the user's settings exactly as written.
        }
        return parse(new String(HistoryPolicy.read(file, 65_536), StandardCharsets.UTF_8));
    }

    static void savePinned(Path directory, boolean pinned) throws IOException {
        AppSettings current = load(directory);
        new AppSettings(current.historyLimit(), pinned, current.theme(), current.sizeNoticeSeconds()).save(directory);
    }

    void save(Path directory) throws IOException {
        ensureDirectory(directory);
        Path file = directory.resolve("settings.json");
        if (Files.isSymbolicLink(file)) {
            throw new IOException("Settings file must not be a symbolic link");
        }
        Path temporary = Files.createTempFile(directory, "settings-", ".tmp",
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(temporary, toJson(), StandardCharsets.UTF_8);
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

    private String toJson() {
        return "{\n  \"historyLimit\": " + historyLimit + ",\n  \"pinned\": " + pinned
                + ",\n  \"theme\": \"" + theme + "\",\n  \"sizeNoticeSeconds\": " + sizeNoticeSeconds + "\n}\n";
    }

    static AppSettings parse(String json) throws IOException {
        String value = json.trim();
        if (!value.startsWith("{") || !value.endsWith("}")) {
            throw invalidSettings();
        }
        String body = value.substring(1, value.length() - 1).trim();
        int historyLimit = DEFAULT_HISTORY_LIMIT;
        boolean pinned = false;
        String theme = "light";
        boolean sawLimit = false;
        boolean sawPinned = false;
        boolean sawTheme = false;
        boolean sawNotice = false;
        int sizeNoticeSeconds = 4;
        if (!body.isEmpty()) {
            for (String field : body.split(",", -1)) {
                Matcher matcher = FIELD.matcher(field);
                if (!matcher.matches()) {
                    throw invalidSettings();
                }
                String name = matcher.group(1);
                String item = matcher.group(2);
                if (name.equals("historyLimit")) {
                    if (sawLimit || !item.matches("[1-9][0-9]*")) {
                        throw invalidSettings();
                    }
                    sawLimit = true;
                    try {
                        historyLimit = Integer.parseInt(item);
                    } catch (NumberFormatException error) {
                        throw new IOException("historyLimit must be between 1 and 500", error);
                    }
                } else if (name.equals("pinned")) {
                    if (sawPinned || (!item.equals("true") && !item.equals("false"))) {
                        throw invalidSettings();
                    }
                    sawPinned = true;
                    pinned = Boolean.parseBoolean(item);
                } else if (name.equals("sizeNoticeSeconds")) {
                    if (sawNotice || !item.matches("0|[2-5]")) throw invalidSettings();
                    sawNotice = true;
                    sizeNoticeSeconds = Integer.parseInt(item);
                } else {
                    if (sawTheme || (!item.equals("\"light\"") && !item.equals("\"dark\""))) {
                        throw invalidSettings();
                    }
                    sawTheme = true;
                    theme = item.substring(1, item.length() - 1);
                }
            }
        }
        if (historyLimit > HistoryPolicy.MAX_ENTRIES) throw invalidSettings();
        return new AppSettings(historyLimit, pinned, theme, sizeNoticeSeconds);
    }

    private static IOException invalidSettings() {
        return new IOException("Expected positive historyLimit, true/false pinned, light/dark theme, and sizeNoticeSeconds 0 or 2–5");
    }

    private static void ensureDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("Settings directory must not be a symbolic link");
        }
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
    }
}
