package top.focess.veto.agent.intercept;

import org.jspecify.annotations.NonNull;
import org.mockito.Mockito;
import top.focess.veto.integration.plugins.PluginManager;

/** Deterministic ingress fixtures without installed masking contributions or a local SLM. */
public final class IngressDefenseTestSupport {
    private IngressDefenseTestSupport() {}

    /** Builds the production protection path with an explicitly empty masking floor. */
    public static @NonNull IngressDefense inMemory() {
        var plugins = Mockito.mock(PluginManager.class);
        Mockito.when(plugins.applyObservationMiddleware(Mockito.anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return new IngressDefense(new SemanticMasker(null, plugins), plugins);
    }
}
