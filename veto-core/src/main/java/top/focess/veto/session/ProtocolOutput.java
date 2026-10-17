package top.focess.veto.session;

import java.util.function.BooleanSupplier;
import org.jspecify.annotations.NonNull;
import top.focess.veto.contract.Frame;

/**
 * Admits a server frame to a transport's bounded output queue without blocking on IO. Carries the
 * producing authority version and attachment for delivery authorization.
 */
@FunctionalInterface
public interface ProtocolOutput {
    boolean send(Frame.@NonNull ServerFrame frame, long version, @NonNull BooleanSupplier attached);
}
