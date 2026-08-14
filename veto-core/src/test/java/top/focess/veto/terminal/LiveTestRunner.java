package top.focess.veto.terminal;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMsg;
import top.focess.veto.contract.IpcCodec;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.Version;

/**
 * Live production-path test: boots the full backend with IpcServer on a real TCP port, connects a
 * raw ZMQ DEALER, and exercises every command just like the real terminal does.
 */
@SpringBootTest(
        properties = {
            "veto.terminal.enabled=true",
            "veto.terminal.bind-address=tcp://127.0.0.1:15570",
            "veto.vault.vault-home=./build/tmp/veto-live",
            "spring.datasource.url=jdbc:h2:mem:veto_live;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        })
class LiveTestRunner {

    private static final @NonNull String ADDR = "tcp://127.0.0.1:15570";

    private ZContext ctx;
    private ZMQ.Socket dealer;

    private void connect() throws Exception {
        ZContext newContext = new ZContext();
        ZMQ.@NonNull Socket newDealer =
                requireValue(
                        newContext.createSocket(SocketType.DEALER),
                        "DEALER socket creation must succeed");
        ctx = newContext;
        dealer = newDealer;
        newDealer.setIdentity(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        newDealer.connect(ADDR);
        Thread.sleep(500);
        send(
                new IpcFrame.Hello(
                        IpcFrame.PROTOCOL_VERSION,
                        1,
                        Version.UNKNOWN,
                        requireValue(System.getProperty("user.dir"), "user.dir is required")));
        IpcFrame welcome = recv();
        assert welcome instanceof IpcFrame.Welcome && ((IpcFrame.Welcome) welcome).seq() == 1;
    }

    private void disconnect() {
        ZMQ.Socket currentDealer = dealer;
        ZContext currentContext = ctx;
        if (currentDealer != null) currentDealer.close();
        if (currentContext != null) currentContext.close();
        dealer = null;
        ctx = null;
    }

    private void send(@NonNull IpcFrame f) throws Exception {
        requireDealer().send(IpcCodec.encode(f));
    }

    private IpcFrame recv() {
        ZMsg msg = ZMsg.recvMsg(requireDealer());
        if (msg == null || msg.isEmpty()) return null;
        byte @NonNull [] data =
                requireValue(
                        requireValue(msg.getFirst(), "non-empty message must have a first frame")
                                .getData(),
                        "message frame data is required");
        String json = new String(data, StandardCharsets.UTF_8);
        msg.destroy();
        return IpcCodec.decode(json);
    }

    private ZMQ.@NonNull Socket requireDealer() {
        ZMQ.Socket current = dealer;
        if (current == null) {
            throw new IllegalStateException("Live test is not connected");
        }
        return current;
    }

    private IpcFrame exchange(@NonNull String cmd) throws Exception {
        send(new IpcFrame.Request(cmd));
        int prompts = 0;
        while (true) {
            IpcFrame f = recv();
            if (f == null) continue;
            if (f instanceof IpcFrame.Done
                    || f instanceof IpcFrame.Error
                    || f instanceof IpcFrame.Terminate) return f;
            if (f instanceof IpcFrame.Prompt) {
                String reply = prompts == 0 ? "liveuser" : "livepass";
                send(new IpcFrame.Input(reply));
                prompts++;
            }
        }
    }

    private static <T extends @NonNull Object> @NonNull T requireValue(T value, String message) {
        if (value != null) {
            return value;
        }
        throw new AssertionError(message);
    }

    // ── live tests ───────────────────────────────────────────────────────

    @Test
    void runAllCommandsLive() throws Exception {
        connect();
        try {
            // 1. /help
            IpcFrame r = exchange("/help");
            System.out.println("[HELP]  -> " + r);
            assert r instanceof IpcFrame.Done : "/help failed: " + r;

            // 2. /status before login
            r = exchange("/status");
            System.out.println("[STATUS (no login)] -> " + r);
            assert r instanceof IpcFrame.Error : "/status should Error before login: " + r;

            // 3. /signup
            r = exchange("/signup");
            System.out.println("[SIGNUP] -> " + r);
            assert r instanceof IpcFrame.Done : "/signup failed: " + r;

            // 4. /login
            r = exchange("/login");
            System.out.println("[LOGIN] -> " + r);
            assert r instanceof IpcFrame.Done : "/login failed: " + r;

            // 5. /logout
            r = exchange("/logout");
            System.out.println("[LOGOUT] -> " + r);
            assert r instanceof IpcFrame.Done : "/logout failed: " + r;

            // 6. Tab completion
            send(new IpcFrame.Complete("/log", 1));
            IpcFrame comp = recv();
            System.out.println("[COMPLETE /log] -> " + comp);
            assert comp instanceof IpcFrame.CompleteResult;

            // 7. Hint
            send(new IpcFrame.Hint("/login ", 2));
            IpcFrame hint = recv();
            System.out.println("[HINT /login ] -> " + hint);
            assert hint instanceof IpcFrame.HintResult;

            // 8. Heartbeat
            send(new IpcFrame.Heartbeat());
            System.out.println("[HEARTBEAT] -> sent");

            // 9. Unknown command
            r = exchange("/nonexistent_cmd_12345");
            System.out.println("[UNKNOWN] -> " + r);
            assert r instanceof IpcFrame.Error;

            // 10. /exit — last: a command-Terminate is session-terminal, so the server closes
            // the session after sending it; nothing after this reaches the session.
            r = exchange("/exit");
            System.out.println("[EXIT] -> " + r);
            assert r instanceof IpcFrame.Terminate : "/exit failed: " + r;

            System.out.println("\n=== ALL COMMANDS PASSED ===");
        } finally {
            disconnect();
        }
    }
}
