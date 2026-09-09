package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.group.GroupHistoryStore;
import top.focess.veto.group.GroupRegistry;
import top.focess.veto.session.SessionRecordService;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;

class SessionGroupControllerTest {
    @Test
    void ownershipIsCheckedBeforeReadingGroupHistory() {
        @NonNull SessionService sessions = mock();
        @NonNull SessionRecordService records = mock();
        @NonNull GroupHistoryStore store = mock();
        @NonNull KeysteadVault vault = mock();
        when(vault.currentUser()).thenReturn("other-user");
        when(sessions.resolveByName("private-session", "other-user")).thenReturn(Optional.empty());
        var controller =
                new SessionGroupController(sessions, records, store, new GroupRegistry(), vault);
        assertThrows(ResponseStatusException.class, () -> controller.groups("private-session"));
        verifyNoInteractions(records, store);
    }
}
