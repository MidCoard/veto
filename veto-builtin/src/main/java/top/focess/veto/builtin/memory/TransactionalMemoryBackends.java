package top.focess.veto.builtin.memory;

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jspecify.annotations.NonNull;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.focess.veto.builtin.memory.embedder.Embedder;

/**
 * Trusted builtin adapter. Owns durable adapter provisioning and account cleanup without core
 * feature SQL.
 */
public final class TransactionalMemoryBackends implements MemoryBackendFactory {
    private final @NonNull EntityManager database;
    private final @NonNull DataSource source;
    private final @NonNull Supplier<@NonNull MemoryRepository> repositories;
    private final @NonNull TransactionTemplate transactions;

    /** Creates the backend factory over the given persistence infrastructure. */
    public TransactionalMemoryBackends(
            @NonNull EntityManager database,
            @NonNull DataSource source,
            @NonNull Supplier<@NonNull MemoryRepository> repositories,
            @NonNull PlatformTransactionManager transactions) {
        this.database = database;
        this.source = source;
        this.repositories = repositories;
        this.transactions = new TransactionTemplate(transactions);
    }

    @Override
    public @NonNull MemoryStore open(@NonNull String profile, @NonNull Embedder embedder) {
        MemoryStore store;
        if (profile.equals("jpa")) store = new JpaMemoryStore(repositories.get(), embedder);
        else if (profile.equals("pgvector")) {
            var vector = new PgvectorMemoryStore(database, embedder);
            transactions.executeWithoutResult(status -> vector.provision());
            store = vector;
        } else throw new IllegalArgumentException("Not a durable memory backend");
        return new MemoryStore() {
            public @NonNull List<ScoredMemory> search(@NonNull MemoryQuery query) {
                var result = transactions.execute(status -> store.search(query));
                if (result == null)
                    throw new IllegalStateException("Memory query returned no result");
                return result;
            }

            public @NonNull MemoryId add(@NonNull Memory memory) {
                var result = transactions.execute(status -> store.add(memory));
                if (result == null) throw new IllegalStateException("Memory write returned no id");
                return result;
            }

            public MemoryId promote(@NonNull MemoryId id, @NonNull UUID user) {
                return transactions.execute(status -> store.promote(id, user));
            }

            public boolean forget(@NonNull MemoryId id, @NonNull UUID user) {
                return Boolean.TRUE.equals(transactions.execute(status -> store.forget(id, user)));
            }

            public void deleteOwner(@NonNull UUID user) {
                TransactionalMemoryBackends.this.deleteOwner(user);
            }

            public void deleteSession(@NonNull UUID user, @NonNull UUID session) {
                TransactionalMemoryBackends.this.deleteSession(user, session);
            }
        };
    }

    @Override
    public void deleteOwner(@NonNull UUID userId) {
        transactions.executeWithoutResult(
                status -> {
                    for (String table : existingTables())
                        database.createNativeQuery(
                                        "DELETE FROM " + table + " WHERE user_id = :user")
                                .setParameter("user", userId.toString())
                                .executeUpdate();
                });
    }

    @Override
    public void deleteSession(@NonNull UUID userId, @NonNull UUID sessionId) {
        transactions.executeWithoutResult(
                status -> {
                    for (String table : existingTables())
                        database.createNativeQuery(
                                        "DELETE FROM "
                                                + table
                                                + " WHERE user_id = :user AND session_id = :session")
                                .setParameter("user", userId.toString())
                                .setParameter("session", sessionId.toString())
                                .executeUpdate();
                });
    }

    private @NonNull List<String> existingTables() {
        List<String> names = new ArrayList<>();
        try (var connection = source.getConnection();
                var tables =
                        connection
                                .getMetaData()
                                .getTables(
                                        connection.getCatalog(),
                                        connection.getSchema(),
                                        "%",
                                        new String[] {"TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME");
                if ("memories".equalsIgnoreCase(name)) names.add("memories");
                else if ("pgvector_memories".equalsIgnoreCase(name)) names.add("pgvector_memories");
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Memory storage metadata unavailable");
        }
        return names;
    }
}
