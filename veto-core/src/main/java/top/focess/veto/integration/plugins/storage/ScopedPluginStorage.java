package top.focess.veto.integration.plugins.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.focess.veto.api.plugin.PluginScope;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.integration.plugins.PluginHostServices;
import top.focess.veto.plugin.runtime.ManagedPlugin;

/** Scoped CAS records share the host transaction and referential deletion boundary. */
@Component
public class ScopedPluginStorage implements PluginStorageFactory {
    private final @NonNull EntityManager database;
    private final @NonNull TransactionTemplate transactions;
    private final @NonNull ObjectMapper mapper;
    private final int maxBytes;

    /** Creates the storage engine; {@code maxBytes} caps one plugin document, must be positive. */
    public ScopedPluginStorage(
            @NonNull EntityManager database,
            @NonNull PlatformTransactionManager transactions,
            @NonNull ObjectMapper mapper,
            @Value("${veto.plugins.storage.max-document-bytes:1048576}") int maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("Storage byte limit must be positive");
        this.database = database;
        this.transactions = new TransactionTemplate(transactions);
        this.mapper = mapper;
        this.maxBytes = maxBytes;
    }

    /** Exposes this factory as a host service so the manager can bind per-plugin storage. */
    @Bean
    public @NonNull PluginHostServices storageHostServices() {
        return new PluginHostServices(Map.of(PluginStorageFactory.class, this));
    }

    @Override
    public @NonNull PluginStorage bind(@NonNull ManagedPlugin plugin) {
        return new PluginStorageBinding(plugin, database, transactions, mapper, maxBytes);
    }

    @Override
    public @NonNull UUID authorizeSession(
            @NonNull PluginStorage storage,
            PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> grant) {
        if (!(storage instanceof PluginStorageBinding bound))
            throw new SecurityException("Unrecognized storage binding");
        return transaction(transactions, () -> bound.validate(grant, PluginScope.SESSION).userId());
    }

    @Override
    public @NonNull UUID authorizeUser(
            @NonNull PluginStorage storage,
            PluginStorage.@NonNull Grant<Scope.@NonNull UserScope> grant) {
        if (!(storage instanceof PluginStorageBinding bound))
            throw new SecurityException("Unrecognized storage binding");
        return transaction(transactions, () -> bound.validate(grant, PluginScope.USER).userId());
    }

    @Override
    public PluginStorage.@NonNull Grant<Scope.@NonNull UserScope> transferUser(
            @NonNull PluginStorage caller,
            PluginStorage.@NonNull Grant<Scope.@NonNull UserScope> grant,
            @NonNull PluginStorage provider) {
        if (!(caller instanceof PluginStorageBinding callerBound)
                || !(provider instanceof PluginStorageBinding providerBound))
            throw new SecurityException("Unrecognized storage binding");
        return transaction(
                transactions,
                () ->
                        providerBound.issueUser(
                                callerBound.validate(grant, PluginScope.USER).userId()));
    }

    @Override
    public PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> transferSession(
            @NonNull PluginStorage caller,
            PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> grant,
            @NonNull PluginStorage provider) {
        if (!(caller instanceof PluginStorageBinding callerBound)
                || !(provider instanceof PluginStorageBinding providerBound))
            throw new SecurityException("Unrecognized storage binding");
        return transaction(
                transactions,
                () ->
                        providerBound.issueSession(
                                callerBound.validate(grant, PluginScope.SESSION).userId(),
                                grant.scope().session()));
    }

    /** Called inside the permanent deletion transaction even when no plugin is loaded. */
    public void deleteSession(@NonNull String session) {
        database.createQuery("delete from PluginRecord r where r.session.id = :session")
                .setParameter("session", session)
                .executeUpdate();
    }

    /** Called inside the permanent deletion transaction even when no plugin is loaded. */
    public void deleteUser(@NonNull UUID userId) {
        database.createQuery("delete from PluginRecord r where r.user.userId = :userId")
                .setParameter("userId", userId)
                .executeUpdate();
    }

    static <T> @NonNull T transaction(
            @NonNull TransactionTemplate transactions, @NonNull Supplier<@NonNull T> operation) {
        T result = transactions.execute(status -> operation.get());
        if (result == null)
            throw new IllegalStateException("Storage transaction returned no result");
        return result;
    }
}
