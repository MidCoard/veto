package top.focess.veto.terminal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMsg;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.contract.Version;

/**
 * Live production-path test: boots the full backend with IpcServer on a real TCP port, connects a
 * raw ZMQ DEALER, and exercises every command just like the real terminal does.
 */
@SpringBootTest(
        properties = {
            "veto.terminal.enabled=true",
            "veto.terminal.bind-address=tcp://127.0.0.1:15570",
            "spring.datasource.url=jdbc:h2:mem:veto_live;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        })
class LiveTestRunner {

    @TempDir private static Path vaultHome;

    @DynamicPropertySource
    static void isolatedVault(@NonNull DynamicPropertyRegistry properties) {
        properties.add(
                "veto.vault.vault-home",
                () -> {
                    var directory = vaultHome;
                    if (directory == null) throw new AssertionError("Vault directory is required");
                    return directory.toString();
                });
    }

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
                new Frame.Hello(
                        Frame.PROTOCOL_VERSION,
                        1,
                        Version.UNKNOWN,
                        requireValue(System.getProperty("user.dir"), "user.dir is required")));
        Frame welcome = recv();
        assert welcome instanceof Frame.Welcome && ((Frame.Welcome) welcome).seq() == 1;
    }

    private void disconnect() {
        ZMQ.Socket currentDealer = dealer;
        ZContext currentContext = ctx;
        if (currentDealer != null) currentDealer.close();
        if (currentContext != null) currentContext.close();
        dealer = null;
        ctx = null;
    }

    private void send(@NonNull Frame f) throws Exception {
        requireDealer().send(FrameCodec.encode(f));
    }

    private Frame recv() {
        ZMsg msg = ZMsg.recvMsg(requireDealer());
        if (msg == null || msg.isEmpty()) return null;
        byte @NonNull [] data =
                requireValue(
                        requireValue(msg.getFirst(), "non-empty message must have a first frame")
                                .getData(),
                        "message frame data is required");
        String json = new String(data, StandardCharsets.UTF_8);
        msg.destroy();
        return FrameCodec.decode(json);
    }

    private ZMQ.@NonNull Socket requireDealer() {
        ZMQ.Socket current = dealer;
        if (current == null) {
            throw new IllegalStateException("Live test is not connected");
        }
        return current;
    }

    private Frame exchange(@NonNull String cmd) throws Exception {
        send(new Frame.Request(cmd));
        int prompts = 0;
        while (true) {
            Frame f = recv();
            if (f == null) continue;
            if (f instanceof Frame.Done || f instanceof Frame.Error || f instanceof Frame.Terminate)
                return f;
            if (f instanceof Frame.Prompt) {
                String reply = prompts == 0 ? "liveuser" : "livepass";
                send(new Frame.Input(reply));
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
            Frame r = exchange("/help");
            System.out.println("[HELP]  -> " + r);
            assert r instanceof Frame.Done : "/help failed: " + r;

            // 2. /status before login
            r = exchange("/status");
            System.out.println("[STATUS (no login)] -> " + r);
            assert r instanceof Frame.Error : "/status should Error before login: " + r;

            // 3. /signup
            r = exchange("/signup");
            System.out.println("[SIGNUP] -> " + r);
            assert r instanceof Frame.Done : "/signup failed: " + r;

            // 4. /login
            r = exchange("/login");
            System.out.println("[LOGIN] -> " + r);
            assert r instanceof Frame.Done : "/login failed: " + r;

            // 5. /logout
            r = exchange("/logout");
            System.out.println("[LOGOUT] -> " + r);
            assert r instanceof Frame.Done : "/logout failed: " + r;

            // 6. Tab completion
            send(new Frame.Complete("/log", 1));
            Frame comp = recv();
            System.out.println("[COMPLETE /log] -> " + comp);
            assert comp instanceof Frame.CompleteResult;

            // 7. Hint
            send(new Frame.Hint("/login ", 2));
            Frame hint = recv();
            System.out.println("[HINT /login ] -> " + hint);
            assert hint instanceof Frame.HintResult;

            // 8. Heartbeat
            send(new Frame.Heartbeat(0));
            System.out.println("[HEARTBEAT] -> sent");

            // 9. Unknown command
            r = exchange("/nonexistent_cmd_12345");
            System.out.println("[UNKNOWN] -> " + r);
            assert r instanceof Frame.Error;

            // 10. /exit — last: a command-Terminate is session-terminal, so the server closes
            // the session after sending it; nothing after this reaches the session.
            r = exchange("/exit");
            System.out.println("[EXIT] -> " + r);
            assert r instanceof Frame.Terminate : "/exit failed: " + r;

            System.out.println("\n=== ALL COMMANDS PASSED ===");
        } finally {
            disconnect();
        }
    }
}
