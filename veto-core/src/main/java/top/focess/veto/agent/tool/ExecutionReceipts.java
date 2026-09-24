package top.focess.veto.agent.tool;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Host-only, single-call receipts. They never enter history or cross an async context boundary. */
@NullMarked
@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
public final class ExecutionReceipts {
    private ExecutionReceipts() {}

    private record Receipt(String id, BooleanSupplier valid) {}

    private static final ConcurrentHashMap<ToolCallContext, Receipt> PENDING =
            new ConcurrentHashMap<>();

    public static void publish(ToolCallContext context, String id, BooleanSupplier valid) {
        if (!context.equals(ToolCallContextHolder.get())
                || !context.executionPermit().callId().equals(ToolCallContextHolder.currentCallId())
                || !valid.getAsBoolean())
            throw new SecurityException("Execution receipt does not match this call");
        if (PENDING.putIfAbsent(context, new Receipt(id, valid)) != null)
            throw new SecurityException("Execution receipt already published");
    }

    public static @Nullable String consume(String callId) {
        var context = ToolCallContextHolder.get();
        if (context == null) return null;
        var receipt = PENDING.remove(context);
        return receipt != null
                        && context.executionPermit().callId().equals(callId)
                        && receipt.valid().getAsBoolean()
                ? receipt.id()
                : null;
    }

    public static void discard(@Nullable ToolCallContext context) {
        if (context != null) PENDING.remove(context);
    }
}
