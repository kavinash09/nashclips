/** Shared safety budget. Counts use UTF-16 units in both Java and Swift. */
final class HistoryPolicy {
    static final int MAX_PINNED = 5;
    static final int MAX_ENTRIES = 500;
    static final int MAX_TEXT_UNITS = 65_536;
    static final int MAX_TOTAL_UNITS = 1_048_576;
    static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    static final int PREVIEW_UNITS = 1000;

    static boolean accepts(String text) {
        return text != null && text.length() <= MAX_TEXT_UNITS && !text.isBlank();
    }

    static String preview(String text) {
        if (text.length() <= PREVIEW_UNITS) return text;
        int end = PREVIEW_UNITS;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "\n… (Use copies the complete text)";
    }

    static java.util.List<String> bounded(java.util.List<String> entries, int limit) {
        var result = new java.util.ArrayList<String>();
        int total = 0;
        for (String text : entries) {
            if (!accepts(text)) continue;
            if (result.size() >= Math.min(limit, MAX_ENTRIES)
                    || total + text.length() > MAX_TOTAL_UNITS) break;
            result.add(text);
            total += text.length();
        }
        return result;
    }

    /** Pinned text is unique, ordered first, and reserves part of the shared text budget. */
    static java.util.List<String> withPins(java.util.List<String> entries,
            java.util.List<String> pinned, int limit) {
        if (pinned.size() > MAX_PINNED || new java.util.HashSet<>(pinned).size() != pinned.size()
                || pinned.stream().anyMatch(text -> !accepts(text)))
            throw new IllegalArgumentException("At most five distinct, valid clips may be pinned");
        var result = new java.util.ArrayList<>(pinned);
        int total = pinned.stream().mapToInt(String::length).sum();
        int regular = 0;
        for (String text : entries) {
            if (pinned.contains(text) || !accepts(text)) continue;
            if (regular >= Math.min(limit, MAX_ENTRIES) || total + text.length() > MAX_TOTAL_UNITS) break;
            result.add(text);
            total += text.length();
            regular++;
        }
        return result;
    }

    static byte[] read(java.nio.file.Path file, int limit) throws java.io.IOException {
        if (!java.nio.file.Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new java.io.IOException("Expected a regular file: " + file);
        try (var input = java.nio.file.Files.newInputStream(file)) {
            byte[] data = input.readNBytes(limit + 1);
            if (data.length > limit) throw new java.io.IOException("File exceeds safety limit: " + file);
            return data;
        }
    }
}
