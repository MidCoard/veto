package top.focess.veto.agent.capability;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import org.jspecify.annotations.NonNull;
import top.focess.veto.llm.core.ToolCall;

/** Calls only the remote tool and endpoint bound at registration, for the authorized caller. */
public sealed interface RemoteCallCapability extends Capability permits RemoteCallCapabilityImpl {
    @NonNull JsonNode call(@NonNull ToolCall call) throws IOException;
}
