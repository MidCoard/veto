package top.focess.veto.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.jspecify.annotations.NonNull;
import top.focess.veto.vault.UserEntity;
import top.focess.veto.vault.UserRegistry;

/** Supplies test administrator decisions through the production user-registry dependency. */
public final class AuthorizationTestSupport {
    private AuthorizationTestSupport() {}

    public static @NonNull RequestAuthorization authorizer(
            @NonNull Predicate<@NonNull UUID> administrator) {
        var users = mock(UserRegistry.class);
        when(users.isAdmin(any(UUID.class)))
                .thenAnswer(
                        invocation -> {
                            var userId = invocation.<UUID>getArgument(0);
                            if (userId == null) throw new AssertionError("Missing userId");
                            return administrator.test(userId);
                        });
        when(users.findByUserId(any(UUID.class)))
                .thenAnswer(
                        invocation -> {
                            var userId = invocation.<UUID>getArgument(0);
                            if (userId == null) throw new AssertionError("Missing userId");
                            var user = mock(UserEntity.class);
                            when(user.getUserId()).thenReturn(userId);
                            return Optional.of(user);
                        });
        return new RequestAuthorization(users);
    }
}
