package top.focess.veto.bus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class HeartbeatManagerTest {

    @Mock
    private BusConfiguration config;

    @InjectMocks
    private HeartbeatManager heartbeatManager;

    @Test
    void testInitialState() {
        assertEquals(0, heartbeatManager.getHeartbeatCount());
        assertFalse(heartbeatManager.getMsSinceLastAck() < 0);
    }

    @Test
    void testRecordAck() {
        heartbeatManager.recordAck();
        assertTrue(heartbeatManager.getMsSinceLastAck() < 1000); // Just recorded
    }
}
