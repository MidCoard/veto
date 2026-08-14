package top.focess.veto.contract;

import org.jspecify.annotations.NonNull;

/**
 * Unchecked exception raised by {@link IpcCodec#encode(IpcFrame)} when a frame cannot be
 * serialized. Serialization of an in-process frame is not expected to fail; a failure indicates a
 * programming error rather than bad input, so it surfaces as an unchecked exception.
 */
@SuppressWarnings("serial")
public final class IpcCodecException extends RuntimeException {

    /**
     * Constructs a new codec exception.
     *
     * @param message the detail message
     * @param cause the underlying serialization cause
     */
    public IpcCodecException(@NonNull String message, @NonNull Throwable cause) {
        super(message, cause);
    }
}
