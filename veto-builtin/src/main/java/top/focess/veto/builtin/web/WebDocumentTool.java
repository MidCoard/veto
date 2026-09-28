package top.focess.veto.builtin.web;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.NativeTool;

/** Typed boundary for operations on a single authorized web document. */
public abstract class WebDocumentTool<T> extends NativeTool<T> {
    public abstract @NonNull WebDocumentCapability documentCapability();

    public abstract @NonNull String execute(
            @NonNull T args, @NonNull WebDocumentCapability document);

    @Override
    public @NonNull String execute(@NonNull T args) {
        return execute(args, documentCapability());
    }
}
