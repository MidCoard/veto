package top.focess.veto.distribution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.builtin.memory.MemoryBackendFactory;
import top.focess.veto.builtin.memory.MemoryRepository;
import top.focess.veto.integration.plugins.PluginServiceGrants;
import top.focess.veto.llm.credential.CredentialResolver;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.plugin.runtime.ManagedPlugin;
import top.focess.veto.vault.UserRegistry;

class BuiltinMemoryInfrastructureTest {
    @Test
    void trustedDatabaseFactoryIsGrantedOnlyToBuiltinAndCreatedLazily() {
        var database = mock(EntityManager.class);
        var transactions = mock(PlatformTransactionManager.class);
        DataSource source = mock(DataSource.class);
        UserRegistry users = mock(ToolDocs.nonNullClass(UserRegistry.class));
        SessionRepository sessions = mock(ToolDocs.nonNullClass(SessionRepository.class));
        var services =
                new BuiltinMemoryInfrastructure()
                        .builtinMemoryServices(
                                new DefaultListableBeanFactory()
                                        .getBeanProvider(
                                                ToolDocs.nonNullClass(MemoryRepository.class)),
                                database,
                                transactions,
                                new MockEnvironment(),
                                mock(ToolDocs.nonNullClass(CredentialResolver.class)),
                                new ObjectMapper(),
                                source,
                                users,
                                sessions);
        assertFalse(
                services.services().containsKey(ToolDocs.nonNullClass(MemoryBackendFactory.class)));
        Object rawGrants = services.services().get(PluginServiceGrants.class);
        if (rawGrants == null) throw new AssertionError("Expected builtin-specific grants");
        PluginServiceGrants grants =
                assertInstanceOf(ToolDocs.nonNullClass(PluginServiceGrants.class), rawGrants);
        var builtin = mock(ManagedPlugin.class);
        when(builtin.identity()).thenReturn(new PluginIdentity("top.focess.builtin", "1.0.0"));
        var other = mock(ManagedPlugin.class);
        when(other.identity()).thenReturn(new PluginIdentity("other.plugin", "1.0.0"));
        assertTrue(
                grants.forPlugin(builtin)
                        .containsKey(ToolDocs.nonNullClass(MemoryBackendFactory.class)));
        assertTrue(grants.forPlugin(other).isEmpty());
        verifyNoInteractions(database, transactions, source, users, sessions);
    }
}
