package top.focess.veto.bus;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HeartbeatManagerTest {

  @Mock private BusConfiguration config;

  @InjectMocks private HeartbeatManager heartbeatManager;

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
