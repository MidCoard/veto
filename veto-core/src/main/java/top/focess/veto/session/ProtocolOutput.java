package top.focess.veto.session;

import java.io.IOException;
import org.jspecify.annotations.NonNull;
import top.focess.veto.contract.Frame;

/** Writes one complete server Frame. The protocol endpoint owns queuing and authorization. */
@FunctionalInterface
public interface ProtocolOutput {
    void send(Frame.@NonNull ServerFrame frame) throws IOException;
}
