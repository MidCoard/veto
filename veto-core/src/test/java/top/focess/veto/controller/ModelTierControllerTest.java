package top.focess.veto.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.focess.veto.model.tier.ModelTierProfileEntity;
import top.focess.veto.model.tier.ModelTierProfileService;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.TestUsers;

class ModelTierControllerTest {
    @Test
    void explicitNullRejectsWholeBindingRequestBeforeAnyWrite() throws Exception {
        ModelTierProfileService profiles = mock(ModelTierProfileService.class);
        KeysteadVault vault = mock(KeysteadVault.class);
        when(vault.currentUser()).thenReturn(TestUsers.ALICE);
        when(profiles.profile(TestUsers.ALICE, "default"))
                .thenReturn(
                        Optional.of(new ModelTierProfileEntity("default", TestUsers.ALICE, true)));
        ModelTierController controller = new ModelTierController(profiles, vault);
        MockMvcBuilders.standaloneSetup(controller)
                .build()
                .perform(
                        put("/api/modeltiers/default/bindings/TOP")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"model\":\"replacement\",\"temp\":null}"))
                .andExpect(status().isBadRequest());
        verify(profiles).profile(TestUsers.ALICE, "default");
        verifyNoMoreInteractions(profiles);
    }
}
