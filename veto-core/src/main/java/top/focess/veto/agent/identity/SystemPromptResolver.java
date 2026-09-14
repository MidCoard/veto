package top.focess.veto.agent.identity;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.loop.PromptLibrary;

/** Default persona metadata. All model instructions are compiled from the bundled MDC library. */
@Component
public class SystemPromptResolver {
    public static final @NonNull String NAME = "VetoCoreAgent";
    public static final @NonNull String DESCRIPTION =
            PromptLibrary.text("default-persona-description");
}
