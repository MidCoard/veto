package top.focess.veto.providers;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.api.llm.PromptRenderer;

final class ProviderTestPrompts {
    private ProviderTestPrompts() {}

    static final @NonNull PromptRenderer PROMPTS =
            (source, data) -> PromptCompiler.compileDocument(source, data).text();
}
