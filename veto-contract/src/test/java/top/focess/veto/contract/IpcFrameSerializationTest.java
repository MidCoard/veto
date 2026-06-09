package top.focess.veto.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IpcFrameSerializationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void deltaWithUsageContentRoundTrips() throws Exception {
        String usage =
                "/pattern create <name> <provider> <model> [sysprompt] — Create a pattern (API key"
                        + " is prompted)\n"
                        + "/pattern list — List your patterns\n"
                        + "/pattern use <name> — Activate a pattern\n"
                        + "/pattern delete <name> — Delete a pattern\n"
                        + "/pattern show <name> — Show pattern details";

        IpcFrame delta = new IpcFrame.Delta(usage, 0);

        // Serialize (same as backend), decode as UTF-8 (same as ZMQ.CHARSET)
        byte[] bytes = JSON.writeValueAsBytes(delta);
        String json = new String(bytes, StandardCharsets.UTF_8);

        System.out.println("Serialized Delta JSON (" + json.length() + " chars):");
        System.out.println(json);
        System.out.println();

        // Deserialize (same as terminal)
        IpcFrame result = ZmqTransport.deserialize(json);
        assertNotNull(result, "deserialize returned null");
        assertInstanceOf(IpcFrame.Delta.class, result);
        IpcFrame.Delta d = (IpcFrame.Delta) result;
        assertEquals(usage, d.content());
        assertEquals(0, d.index());
    }

    @Test
    void deltaAndDoneRoundTrip() throws Exception {
        // Simulate the exact sequence: Delta then Done
        String usage = "/pattern create <name> — test\n/pattern list — test";

        IpcFrame delta = new IpcFrame.Delta(usage, 0);
        IpcFrame done = new IpcFrame.Done();

        String deltaJson = new String(JSON.writeValueAsBytes(delta), StandardCharsets.UTF_8);
        String doneJson = new String(JSON.writeValueAsBytes(done), StandardCharsets.UTF_8);

        System.out.println("Delta JSON: " + deltaJson);
        System.out.println("Done JSON:  " + doneJson);

        IpcFrame deltaResult = ZmqTransport.deserialize(deltaJson);
        IpcFrame doneResult = ZmqTransport.deserialize(doneJson);

        assertNotNull(deltaResult, "Delta deserialization returned null");
        assertInstanceOf(IpcFrame.Delta.class, deltaResult);
        assertNotNull(doneResult, "Done deserialization returned null");
        assertInstanceOf(IpcFrame.Done.class, doneResult);
    }

    @Test
    void serializeDeserializeAllFrameTypes() throws Exception {
        for (IpcFrame f :
                new IpcFrame[] {
                    new IpcFrame.Request("test", 1),
                    new IpcFrame.Delta("hello world", 0),
                    new IpcFrame.Done(Map.of("exit", true), "ok", 1),
                    new IpcFrame.Error("fail", 1),
                    new IpcFrame.Prompt("enter:", Map.of("mask", true)),
                }) {
            String json = new String(JSON.writeValueAsBytes(f), StandardCharsets.UTF_8);
            IpcFrame result = ZmqTransport.deserialize(json);
            assertNotNull(result, "null for " + f.getClass().getSimpleName() + " json=" + json);
            assertEquals(f.getClass(), result.getClass(), "wrong type for " + json);
        }
    }
}
