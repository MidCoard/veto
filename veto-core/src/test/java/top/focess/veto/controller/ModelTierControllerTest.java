package top.focess.veto.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.model.tier.ModelTierProfileEntity;
import top.focess.veto.model.tier.ModelTierProfileService;
import top.focess.veto.vault.KeysteadVault;

class ModelTierControllerTest {
    @Test
    void explicitNullRejectsWholeBindingRequestBeforeAnyWrite() throws Exception {
        ModelTierProfileService profiles =
                mock(ToolDocs.nonNullClass(ModelTierProfileService.class));
        KeysteadVault vault = mock(ToolDocs.nonNullClass(KeysteadVault.class));
        when(vault.currentUser()).thenReturn("alice");
        when(profiles.profile("alice", "default"))
                .thenReturn(Optional.of(new ModelTierProfileEntity("default", "alice", true)));
        ModelTierController controller = new ModelTierController(profiles, vault);
        MockMvcBuilders.standaloneSetup(controller)
                .build()
                .perform(
                        put("/api/modeltiers/default/bindings/TOP")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"model\":\"replacement\",\"temp\":null}"))
                .andExpect(status().isBadRequest());
        verify(profiles).profile("alice", "default");
        verifyNoMoreInteractions(profiles);
    }
}
