package top.focess.veto.agent.tool;

import java.util.concurrent.Callable;
import org.jspecify.annotations.NonNull;

/** Test engines emulate the same call-id boundary as ToolEngineImpl. */
public final class ToolInvocationFixture {
    private ToolInvocationFixture() {}

    public static <T> T call(@NonNull String id, @NonNull Callable<T> operation) throws Exception {
        ToolCallContextHolder.setCurrentCallId(id);
        try {
            return operation.call();
        } finally {
            ToolCallContextHolder.setCurrentCallId("");
        }
    }
}
