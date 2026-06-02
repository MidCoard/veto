package top.focess.veto.orchestrator;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * C5 File Lock Manager - manages local file-lock contention between worker processes. Prevents
 * concurrent access to shared files across the swarm.
 */
@Component
public class FileLockManager {

    private static final Logger log = LoggerFactory.getLogger(FileLockManager.class);

    private final ConcurrentHashMap<Path, FileLockHandle> activeLocks = new ConcurrentHashMap<>();

    /**
     * Acquire a file lock with a timeout.
     *
     * @param path The file path to lock
     * @param timeout Maximum time to wait for the lock
     * @param unit Time unit for timeout
     * @return true if lock was acquired, false if timed out
     * @throws IOException if lock acquisition fails
     */
    public boolean acquireLock(Path path, long timeout, TimeUnit unit) throws IOException {
        // Ensure the lock file exists
        Path lockPath = path.resolveSibling(path.getFileName() + ".lock");
        Files.createDirectories(lockPath.getParent());
        if (!Files.exists(lockPath)) {
            Files.createFile(lockPath);
        }

        try {
            FileChannel channel =
                    FileChannel.open(lockPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
            FileLock lock = channel.tryLock(0L, Long.MAX_VALUE, false);

            if (lock != null) {
                activeLocks.put(path, new FileLockHandle(channel, lock));
                log.debug("C5 FileLock: Acquired lock for {}", path);
                return true;
            }

            // Couldn't acquire immediately, wait with timeout
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (System.nanoTime() < deadline) {
                Thread.sleep(100);
                lock = channel.tryLock(0L, Long.MAX_VALUE, false);
                if (lock != null) {
                    activeLocks.put(path, new FileLockHandle(channel, lock));
                    log.debug("C5 FileLock: Acquired lock for {} (after wait)", path);
                    return true;
                }
            }

            channel.close();
            log.warn("C5 FileLock: Timeout waiting for lock on {}", path);
            return false;

        } catch (OverlappingFileLockException e) {
            log.warn("C5 FileLock: Overlapping lock for {} (already held in this JVM)", path);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("C5 FileLock: Interrupted while waiting for lock on {}", path);
            return false;
        }
    }

    /**
     * Release a previously acquired file lock.
     */
    public void releaseLock(Path path) {
        FileLockHandle handle = activeLocks.remove(path);
        if (handle != null) {
            try {
                handle.lock.release();
                handle.channel.close();
                log.debug("C5 FileLock: Released lock for {}", path);
            } catch (IOException e) {
                log.warn("C5 FileLock: Error releasing lock for {}", path, e);
            }
        }
    }

    /** Check if a path is currently locked. */
    public boolean isLocked(Path path) {
        FileLockHandle handle = activeLocks.get(path);
        return handle != null && handle.lock.isValid();
    }

    /** Release all locks (called during shutdown). */
    public void releaseAll() {
        for (Path p : activeLocks.keySet()) {
            releaseLock(p);
        }
        log.info("C5 FileLock: Released all locks");
    }

    private record FileLockHandle(FileChannel channel, FileLock lock) {}
}
