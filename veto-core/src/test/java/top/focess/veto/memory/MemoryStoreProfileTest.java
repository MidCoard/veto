package top.focess.veto.memory;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Regression for the {@link MemoryStore} bean-conflict: {@link InMemoryMemoryStore} must be
 * conditional (default, {@code matchIfMissing}) so activating a non-default backend ({@code
 * veto.memory.store=vector}) resolves a <em>single</em> {@link MemoryStore} bean instead of two
 * (which previously made {@code MemoryCaptureService} fail with {@code
 * NoUniqueBeanDefinitionException}).
 */
@SpringBootTest(properties = "veto.memory.store=vector")
class MemoryStoreProfileTest {

    @Autowired MemoryStore memoryStore;

    @Test
    void vectorProfileResolvesSingleVectorBackedStore() {
        assertInstanceOf(
                VectorIndexMemoryStore.class,
                memoryStore,
                "veto.memory.store=vector must resolve the vector-backed store with no bean conflict");
    }
}
