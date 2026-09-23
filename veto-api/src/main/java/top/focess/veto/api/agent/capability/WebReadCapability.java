package top.focess.veto.api.agent.capability;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.web.ReaderSession;

public interface WebReadCapability extends Capability, AutoCloseable {
    @NonNull String read(@NonNull String objective, ReaderSession.@NonNull Factory factory);

    void close();
}
