package top.focess.veto.agent.mcp;

import org.jspecify.annotations.NonNull;

/** Expected tool-level failure returned with {@code success=false} and a diagnostic body. */
@SuppressWarnings("serial")
public final class ToolExecutionException extends RuntimeException {

    public ToolExecutionException(@NonNull String message) {
        super(message);
    }
}
