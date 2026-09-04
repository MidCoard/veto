package top.focess.veto.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.vault.AuthLifecycleManager;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.SessionManager;
import top.focess.veto.vault.UserRegistry;

class AuthControllerTest {
    @Test
    void invalidRegistrationNeverCreatesAUserOrVault() throws Exception {
        UserRegistry users = mock(ToolDocs.nonNullClass(UserRegistry.class));
        SessionManager sessions = mock(ToolDocs.nonNullClass(SessionManager.class));
        KeysteadVault vault = mock(ToolDocs.nonNullClass(KeysteadVault.class));
        AuthLifecycleManager lifecycle = mock(ToolDocs.nonNullClass(AuthLifecycleManager.class));
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new AuthController(users, sessions, vault, lifecycle))
                        .build();
        for (String body :
                List.of(
                        "{}",
                        "{\"username\":\"alice\",\"password\":null}",
                        "{\"username\":\"../invalid\",\"password\":\"password123\"}",
                        "{\"username\":\"alice\",\"password\":\"short\"}")) {
            mvc.perform(
                            post("/api/auth/setup")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(users, sessions, vault, lifecycle);
    }
}
