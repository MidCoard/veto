package top.focess.veto.contract;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages a single memory-mapped IPC file for terminal ↔ backend communication.
 *
 * <h3>File layout</h3>
 *
 * <pre>{@code
 * Offset  Size  Field
 * 0       1     State byte (IpcState)
 * 1       3     Reserved (zero)
 * 4       4     Payload length (int, big-endian)
 * 8       N     Payload (UTF-8 JSON bytes)
 * }</pre>
 *
 * <p>Mapped once at creation time; reused for the lifetime of the terminal session. Thread-safe via
 * {@link FileLock} — callers must hold the lock before reading or writing.
 */
public final class IpcFile implements AutoCloseable {

    static final int HEADER_SIZE = 8;
    static final int OFFSET_STATE = 0;
    static final int OFFSET_LENGTH = 4;
    static final int OFFSET_PAYLOAD = 8;

    private final Path path;
    private final int fileSize;
    private final RandomAccessFile raf;
    private final FileChannel channel;
    private final MappedByteBuffer buffer;

    /**
     * Open (or create) an IPC file at {@code path} with the given total size in bytes. The file is
     * memory-mapped for read/write.
     */
    public IpcFile(Path path, int fileSize) throws IOException {
        this.path = path;
        this.fileSize = fileSize;
        Files.createDirectories(path.getParent());
        boolean existed = Files.exists(path);

        this.raf = new RandomAccessFile(path.toFile(), "rw");
        this.channel = raf.getChannel();

        if (!existed || raf.length() < fileSize) {
            raf.setLength(fileSize);
        }

        this.buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, fileSize);
        if (!existed) {
            buffer.put(OFFSET_STATE, IpcState.IDLE.code());
        }
    }

    /** Read the current state byte (no lock check — caller must hold lock). */
    public IpcState readState() {
        // Reload position for fresh read from the mapped region
        buffer.position(OFFSET_STATE);
        return IpcState.from(buffer.get());
    }

    /** Write a new state byte. Call {@link #flush()} after to commit. */
    public void writeState(IpcState state) {
        buffer.position(OFFSET_STATE);
        buffer.put(state.code());
    }

    /** Read payload length (big-endian int). */
    public int readLength() {
        buffer.position(OFFSET_LENGTH);
        return buffer.getInt();
    }

    /** Write payload length (big-endian int). Call {@link #flush()} after to commit. */
    public void writeLength(int length) {
        buffer.position(OFFSET_LENGTH);
        buffer.putInt(length);
    }

    /** Read {@code len} bytes from the payload region into a new array. */
    public byte[] readPayload(int len) {
        byte[] data = new byte[len];
        buffer.position(OFFSET_PAYLOAD);
        buffer.get(data, 0, len);
        return data;
    }

    /** Write payload bytes and update the length field. Call {@link #flush()} after to commit. */
    public void writePayload(byte[] data) {
        writeLength(data.length);
        buffer.position(OFFSET_PAYLOAD);
        buffer.put(data);
    }

    /**
     * Force any changes to the mapped buffer out to the backing file so they are visible to the
     * other process. Must be called after writes and before releasing the file lock.
     */
    public void flush() {
        buffer.force();
    }

    /** Maximum payload size this file can hold. */
    public int maxPayloadSize() {
        return fileSize - HEADER_SIZE;
    }

    /** Acquire an exclusive lock, blocking until available. */
    public FileLock lock() throws IOException {
        return channel.lock();
    }

    /**
     * Try to acquire an exclusive lock, returning {@code null} immediately if it is not available.
     */
    public FileLock tryLock() throws IOException {
        return channel.tryLock();
    }

    /** Release a previously acquired lock. */
    public void unlock(FileLock lock) throws IOException {
        if (lock != null && lock.isValid()) {
            lock.release();
        }
    }

    public Path path() {
        return path;
    }

    public int fileSize() {
        return fileSize;
    }

    public File file() {
        return path.toFile();
    }

    public String filename() {
        return path.getFileName().toString();
    }

    /** Extract the terminal ID from the filename (strips {@code .ipc} suffix). */
    public String terminalId() {
        String name = filename();
        return name.endsWith(".ipc") ? name.substring(0, name.length() - 4) : name;
    }

    @Override
    public void close() throws IOException {
        if (channel.isOpen()) channel.close();
        raf.close();
    }

    /** Close and delete the backing file. */
    public void delete() throws IOException {
        close();
        Files.deleteIfExists(path);
    }
}
