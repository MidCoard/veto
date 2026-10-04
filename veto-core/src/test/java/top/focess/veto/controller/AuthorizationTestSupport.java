package top.focess.veto.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.function.Predicate;
import org.jspecify.annotations.NonNull;
import top.focess.veto.vault.UserRegistry;

/** Supplies test administrator decisions through the production user-registry dependency. */
public final class AuthorizationTestSupport {
    private AuthorizationTestSupport() {}

    public static @NonNull RequestAuthorization authorizer(
            @NonNull Predicate<@NonNull String> administrator) {
        var users = mock(UserRegistry.class);
        when(users.isAdmin(anyString()))
                .thenAnswer(
                        invocation -> {
                            var username = invocation.<String>getArgument(0);
                            if (username == null) throw new AssertionError("Missing username");
                            return administrator.test(username);
                        });
        return new RequestAuthorization(users);
    }
}
