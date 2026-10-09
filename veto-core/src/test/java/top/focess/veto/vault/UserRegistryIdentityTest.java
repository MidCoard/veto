package top.focess.veto.vault;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class UserRegistryIdentityTest {
    @Test
    void databaseDuplicateAfterExistenceCheckBecomesABusinessConflict() {
        var repository = mock(UserRepository.class);
        var violation = new DataIntegrityViolationException("duplicate username");
        when(repository.existsByUsername("alice")).thenReturn(false);
        when(repository.saveAndFlush(any(UserEntity.class))).thenThrow(violation);
        var registry = new UserRegistry(repository);
        var rejected =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> registry.create("alice", "test-password", UserRegistry.Role.USER));
        assertEquals("User 'alice' already exists", rejected.getMessage());
        var cause = rejected.getCause();
        if (cause == null) throw new AssertionError("Duplicate conflict lost its database cause");
        assertSame(violation, cause);
    }

    @Test
    void passwordResetPreservesIdentityButUsernameRecreationDoesNot() {
        var original =
                new UserEntity(
                        "alice",
                        new byte[] {1},
                        new byte[] {2},
                        UserRegistry.Role.USER,
                        Instant.now());
        var userId = original.getUserId();
        var repository = mock(UserRepository.class);
        when(repository.findById(userId)).thenReturn(Optional.of(original));
        var registry = new UserRegistry(repository);
        registry.setPassword(userId, "replacement-password");
        assertEquals(userId, original.getUserId());
        verify(repository).save(same(original));
        var recreated =
                new UserEntity(
                        "alice",
                        new byte[] {3},
                        new byte[] {4},
                        UserRegistry.Role.USER,
                        Instant.now());
        assertNotEquals(userId, recreated.getUserId());
    }
}
