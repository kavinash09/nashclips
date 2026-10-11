import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Remembers a fingerprint, never the rejected clipboard payload. */
final class RejectedClipTracker {
    private byte[] previous;

    boolean observe(String text) {
        if (text == null || text.length() <= HistoryPolicy.MAX_TEXT_UNITS) {
            previous = null;
            return false;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int used = 0;
            for (int i = 0; i < text.length(); i++) {
                char unit = text.charAt(i);
                buffer[used++] = (byte) (unit >>> 8);
                buffer[used++] = (byte) unit;
                if (used == buffer.length) { digest.update(buffer); used = 0; }
            }
            digest.update(buffer, 0, used);
            byte[] next = digest.digest();
            boolean changed = !Arrays.equals(previous, next);
            previous = next;
            return changed;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by Java", impossible);
        }
    }

    void clear() { previous = null; }
}
