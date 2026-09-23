package top.focess.veto.api.web;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.NativeTool;
import top.focess.veto.api.agent.tool.ToolExecutionException;

public interface ReaderSession extends AutoCloseable {
    interface Runtime {
        void authorize(@NonNull String operation);

        @NonNull FetchedPage fetch();

        @NonNull Execution execution();

        void completed();
    }

    @FunctionalInterface
    interface Factory {
        @NonNull ReaderSession open(@NonNull Runtime runtime);
    }

    @NonNull List<NativeTool<?>> tools();

    void setObservationBudget(int bytes);

    Result result();

    ToolExecutionException failure();

    void close();
}
