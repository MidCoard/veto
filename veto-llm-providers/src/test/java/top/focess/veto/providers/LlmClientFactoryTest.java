package top.focess.veto.providers;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

class LlmClientFactoryTest {
    private static final class Client implements AutoCloseable {
        private final String endpoint;
        private final @NonNull String credential;
        private int closes;

        private Client(String endpoint, @NonNull String credential) {
            this.endpoint = endpoint;
            this.credential = credential;
        }

        @Override
        public void close() {
            closes++;
        }
    }

    @Test
    void exactCredentialsAndNullableEndpointsOwnDistinctClientsAndCloseOnce() {
        List<@NonNull Client> built = new ArrayList<>();
        try (var factory = new LlmClientFactory(new ObjectMapper(), (source, data) -> "unused")) {
            factory.register(
                    Client.class,
                    (endpoint, credential) -> {
                        var client = new Client(endpoint, credential);
                        built.add(client);
                        return client;
                    });
            assertEquals("Aa".hashCode(), "BB".hashCode(), "Synthetic collision is intentional");
            var first = factory.get(Client.class, "endpoint", "Aa");
            var collision = factory.get(Client.class, "endpoint", "BB");
            assertNotSame(first, collision);
            assertEquals("Aa", first.credential);
            assertEquals("BB", collision.credential);
            assertSame(first, factory.get(Client.class, "endpoint", String.join("", "A", "a")));
            assertSame(collision, factory.get(Client.class, "endpoint", "BB"));

            var absent = factory.get(Client.class, null, "Aa");
            var literal = factory.get(Client.class, "null", "Aa");
            assertNotSame(absent, literal);
            assertNull(absent.endpoint);
            assertEquals("null", literal.endpoint);
            assertSame(absent, factory.get(Client.class, null, "Aa"));
            assertSame(literal, factory.get(Client.class, "null", "Aa"));
            assertEquals(4, built.size());
        }
        assertEquals(4, built.size());
        for (var client : built) assertEquals(1, client.closes);
    }
}
