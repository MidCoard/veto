package top.focess.veto.distribution;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.builtin.memory.MemoryBackendFactory;
import top.focess.veto.builtin.memory.MemoryRepository;
import top.focess.veto.builtin.memory.TransactionalMemoryBackends;
import top.focess.veto.integration.plugins.PluginEmbeddingFactory;
import top.focess.veto.integration.plugins.PluginHostServices;
import top.focess.veto.integration.plugins.PluginServiceGrants;
import top.focess.veto.integration.plugins.PluginTextEmbeddings;
import top.focess.veto.llm.credential.CredentialResolver;
import top.focess.veto.llm.embedding.EmbeddingProfile;
import top.focess.veto.llm.embedding.ProviderEmbeddingClient;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.vault.UserRegistry;

/** Optional distribution wiring. All feature backends and schema types are owned by builtin. */
@Configuration
public class BuiltinMemoryInfrastructure {
    @Bean
    public @NonNull PluginHostServices builtinMemoryServices(
            @NonNull ObjectProvider<MemoryRepository> repositories,
            @NonNull EntityManager database,
            @NonNull PlatformTransactionManager transactions,
            @NonNull Environment environment,
            @NonNull CredentialResolver credentials,
            @NonNull ObjectMapper mapper,
            @NonNull DataSource source,
            @NonNull UserRegistry users,
            @NonNull SessionRepository sessions) {
        MemoryBackendFactory backends =
                new TransactionalMemoryBackends(
                        database,
                        source,
                        repositories::getObject,
                        () ->
                                users.listAll().stream()
                                        .map(
                                                user ->
                                                        new TransactionalMemoryBackends.Account(
                                                                user.getUsername(),
                                                                UUID.fromString(
                                                                        user.storageIdentity()),
                                                                user.getCreatedAt(),
                                                                sessions
                                                                        .findByOwner(
                                                                                user.getUsername())
                                                                        .stream()
                                                                        .filter(
                                                                                session ->
                                                                                        !session.getCreatedAt()
                                                                                                .isBefore(
                                                                                                        user
                                                                                                                .getCreatedAt()))
                                                                        .map(
                                                                                session ->
                                                                                        session
                                                                                                .getId())
                                                                        .toList()))
                                        .toList(),
                        transactions);
        PluginServiceGrants grants =
                plugin ->
                        plugin.identity().id().equals("top.focess.builtin")
                                ? Map.of(
                                        ToolDocs.nonNullClass(MemoryBackendFactory.class), backends)
                                : Map.of();
        String provider = environment.getProperty("veto.memory.embedder.provider", "");
        if (provider.isBlank())
            return new PluginHostServices(Map.of(PluginServiceGrants.class, grants));
        var profile = new EmbeddingProfile();
        profile.setProvider(provider);
        profile.setModel(environment.getProperty("veto.memory.embedder.model"));
        profile.setBaseUrl(environment.getProperty("veto.memory.embedder.base-url"));
        profile.setCredentialKey(environment.getProperty("veto.memory.embedder.credential-key"));
        profile.setDimension(
                environment.getProperty("veto.memory.embedder.dimension", Integer.class, 64));
        var model = new ProviderEmbeddingClient(profile, credentials, mapper);
        PluginEmbeddingFactory embeddings =
                plugin ->
                        plugin.identity().id().equals("top.focess.builtin")
                                ? Optional.of(new PluginTextEmbeddings(plugin, model))
                                : Optional.empty();
        return new PluginHostServices(
                Map.of(
                        PluginServiceGrants.class,
                        grants,
                        PluginEmbeddingFactory.class,
                        embeddings));
    }
}
