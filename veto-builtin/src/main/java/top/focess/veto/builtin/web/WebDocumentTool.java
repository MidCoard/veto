package top.focess.veto.builtin.web;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.NativeTool;

/** Typed boundary for operations on a single authorized web document. */
public interface WebDocumentTool<T> extends NativeTool<T> {
    @NonNull WebDocumentCapability documentCapability();

    @NonNull String execute(@NonNull T args, @NonNull WebDocumentCapability document);

    @Override
    default @NonNull String execute(@NonNull T args) {
        return execute(args, documentCapability());
    }
}
