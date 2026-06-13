package top.focess.veto.terminal;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMsg;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.ZmqTransport;

/**
 * Live production-path test: boots the full backend with ZmqServer on a real TCP port, connects a
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

    private static final String ADDR = "tcp://127.0.0.1:15570";

    private ZContext ctx;
    private ZMQ.Socket dealer;

    private void connect() throws Exception {
        ctx = new ZContext();
        dealer = ctx.createSocket(SocketType.DEALER);
        dealer.setIdentity(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        dealer.connect(ADDR);
        Thread.sleep(500);
        send(new IpcFrame.Hello(IpcFrame.PROTOCOL_VERSION, 1));
        IpcFrame welcome = recv();
        assert welcome instanceof IpcFrame.Welcome && ((IpcFrame.Welcome) welcome).seq() == 1;
    }

    private void disconnect() {
        if (dealer != null) dealer.close();
        if (ctx != null) ctx.close();
    }

    private void send(IpcFrame f) throws Exception {
        dealer.send(ZmqTransport.serialize(f));
    }

    private IpcFrame recv() {
        ZMsg msg = ZMsg.recvMsg(dealer);
        if (msg == null || msg.isEmpty()) return null;
        byte[] data = msg.getFirst().getData();
        String json = new String(data, StandardCharsets.UTF_8);
        msg.destroy();
        return ZmqTransport.deserialize(json);
    }

    private IpcFrame exchange(String cmd) throws Exception {
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

            // 6. /exit
            r = exchange("/exit");
            System.out.println("[EXIT] -> " + r);
            assert r instanceof IpcFrame.Terminate : "/exit failed: " + r;

            // 7. Tab completion
            send(new IpcFrame.Complete("/log", 1));
            IpcFrame comp = recv();
            System.out.println("[COMPLETE /log] -> " + comp);
            assert comp instanceof IpcFrame.CompleteResult;

            // 8. Hint
            send(new IpcFrame.Hint("/login ", 2));
            IpcFrame hint = recv();
            System.out.println("[HINT /login ] -> " + hint);
            assert hint instanceof IpcFrame.HintResult;

            // 9. Heartbeat
            send(new IpcFrame.Heartbeat());
            System.out.println("[HEARTBEAT] -> sent");

            // 10. Unknown command
            r = exchange("/nonexistent_cmd_12345");
            System.out.println("[UNKNOWN] -> " + r);
            assert r instanceof IpcFrame.Error;

            System.out.println("\n=== ALL COMMANDS PASSED ===");
        } finally {
            disconnect();
        }
    }
}
