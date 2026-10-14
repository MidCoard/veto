package top.focess.veto.session;

import static org.mockito.Mockito.*;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import top.focess.veto.agent.AgentService;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.model.SessionRepository;

public final class TestAgentSessions {
    private TestAgentSessions() {}

    public static @NonNull AgentSessions create() {
        return create(mock(SessionRepository.class), mock(AgentService.class));
    }

    @SuppressWarnings("unchecked")
    public static @NonNull AgentSessions create(
            @NonNull SessionRepository repository, @NonNull AgentService agents) {
        ObjectProvider<AgentService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(agents);
        return new AgentSessions(repository, provider);
    }

    public static @NonNull AgentSessions bind(
            @NonNull SessionService service, @NonNull AgentService agents) {
        var runtime = create(mock(SessionRepository.class), agents);
        when(service.runtimeSession(anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            String id = invocation.getArgument(0);
                            UUID user = invocation.getArgument(1);
                            if (id == null || user == null)
                                throw new AssertionError("Missing runtime identity");
                            return runtime.session(new Scope.SessionScope(user, id));
                        });
        return runtime;
    }
}
