package top.focess.veto.agent.tool;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.NonNull;

/** Host-only, single-call receipts. They never enter history or cross an async context boundary. */
public final class ExecutionReceipts {
    private ExecutionReceipts() {}

    private record Receipt(@NonNull String id, @NonNull BooleanSupplier valid) {}

    private static final @NonNull ConcurrentHashMap<@NonNull ToolCallContext, @NonNull Receipt>
            PENDING = new ConcurrentHashMap<>();

    /**
     * Records a single-use receipt for the current call. Rejects any context, call-id, or validity
     * mismatch and a second publication for the same context.
     */
    public static void publish(
            @NonNull ToolCallContext context, @NonNull String id, @NonNull BooleanSupplier valid) {
        if (!context.equals(ToolCallContextHolder.get())
                || !context.executionPermit().callId().equals(ToolCallContextHolder.currentCallId())
                || !valid.getAsBoolean())
            throw new SecurityException("Execution receipt does not match this call");
        if (PENDING.putIfAbsent(context, new Receipt(id, valid)) != null)
            throw new SecurityException("Execution receipt already published");
    }

    /**
     * Consumes the receipt published for the current context, returning its id only when the call
     * id matches and the receipt is still valid; otherwise {@code null}.
     */
    public static String consume(@NonNull String callId) {
        var context = ToolCallContextHolder.get();
        if (context == null) return null;
        var receipt = PENDING.remove(context);
        return receipt != null
                        && context.executionPermit().callId().equals(callId)
                        && receipt.valid().getAsBoolean()
                ? receipt.id()
                : null;
    }

    /**
     * Drops any unconsumed receipt for the given context; a no-op when the context is {@code null}.
     */
    public static void discard(ToolCallContext context) {
        if (context != null) PENDING.remove(context);
    }
}
