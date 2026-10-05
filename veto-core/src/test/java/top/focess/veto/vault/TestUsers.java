package top.focess.veto.vault;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** Stable account fixtures shared by identity-sensitive service and filesystem tests. */
public final class TestUsers {
    public static final @NonNull UUID ALICE =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final @NonNull UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");
    public static final @NonNull UUID ADMIN =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    public static final @NonNull UUID OWNER =
            UUID.fromString("44444444-4444-4444-4444-444444444444");

    private TestUsers() {}

    public static @NonNull UserRegistry registry() {
        var registry = mock(UserRegistry.class);
        Map<@NonNull UUID, @NonNull String> names =
                Map.of(ALICE, "alice", BOB, "bob", ADMIN, "admin", OWNER, "owner");
        names.forEach(
                (userId, username) -> {
                    var user = mock(UserEntity.class);
                    when(user.getUserId()).thenReturn(userId);
                    when(user.getUsername()).thenReturn(username);
                    when(user.getRole())
                            .thenReturn(
                                    ADMIN.equals(userId)
                                            ? UserRegistry.Role.ADMIN
                                            : UserRegistry.Role.USER);
                    when(registry.findByUserId(userId)).thenReturn(Optional.of(user));
                    when(registry.findByUsername(username)).thenReturn(Optional.of(user));
                });
        when(registry.isAdmin(ADMIN)).thenReturn(true);
        return registry;
    }
}
