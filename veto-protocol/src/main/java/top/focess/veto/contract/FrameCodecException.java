package top.focess.veto.contract;

import org.jspecify.annotations.NonNull;

/**
 * Unchecked exception raised by {@link FrameCodec#encode(Frame)} when a frame cannot be serialized.
 * Serialization of an in-process frame is not expected to fail; a failure indicates a programming
 * error rather than bad input, so it surfaces as an unchecked exception.
 */
public final class FrameCodecException extends RuntimeException {

    /**
     * Constructs a new codec exception.
     *
     * @param message the detail message
     * @param cause the underlying serialization cause
     */
    public FrameCodecException(@NonNull String message, @NonNull Throwable cause) {
        super(message, cause);
    }
}
