/** Owns native AX references. All access and disposal are serialized on this instance. */
final class NativeFocus implements AutoCloseable {
    private static final boolean AVAILABLE;
    static {
        boolean loaded = false;
        try {
            System.load(java.nio.file.Path.of("native", "libnashclip-focus.dylib").toAbsolutePath().toString());
            loaded = true;
        } catch (LinkageError | SecurityException error) {
            System.err.println("Window verification unavailable. Rebuild native helpers; use manual paste meanwhile.");
        }
        AVAILABLE = loaded;
    }
    private long handle = AVAILABLE ? create() : 0;
    synchronized void remember() { if (handle != 0) capture(handle); }
    synchronized long freeze() { return handle == 0 ? 0 : snapshot(handle); }
    synchronized boolean restore(long token) { return handle != 0 && token != 0 && activate(handle, token); }
    synchronized boolean isOriginalWindow(long token) { return handle != 0 && token != 0 && matches(handle, token); }
    synchronized void release(long token) { if (handle != 0) releaseSnapshot(handle, token); }
    @Override public synchronized void close() {
        if (handle != 0) { dispose(handle); handle = 0; }
    }
    private static native long create();
    private static native void capture(long handle);
    private static native long snapshot(long handle);
    private static native boolean activate(long handle, long token);
    private static native boolean matches(long handle, long token);
    private static native void releaseSnapshot(long handle, long token);
    private static native void dispose(long handle);
}
