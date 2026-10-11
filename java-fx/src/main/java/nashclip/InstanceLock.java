package nashclip;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

/** A POSIX record lock shared with the Swift variant. Never delete the lock file. */
final class InstanceLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    InstanceLock(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)) throw new IOException("Data directory is a symbolic link");
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
        Path file = directory.resolve("instance.lock");
        channel = FileChannel.open(file, java.util.Set.of(StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        FileLock acquired;
        try {
            acquired = channel.tryLock();
            if (acquired == null) throw new IOException("Another nashclip version is already running. Quit it first.");
        } catch (IOException | OverlappingFileLockException error) {
            channel.close();
            throw new IOException("Cannot lock nashclip data. Another instance may be running.", error);
        }
        lock = acquired;
    }

    @Override public void close() throws IOException {
        try { if (lock.isValid()) lock.release(); }
        finally { channel.close(); }
    }
}
