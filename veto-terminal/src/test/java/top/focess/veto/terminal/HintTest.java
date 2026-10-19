package top.focess.veto.terminal;

import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.ProtocolClient;
import top.focess.veto.transport.tcp.TcpConnection;

/** Standalone hint protocol test — connects to a running backend and tests live hints. */
public class HintTest {
    public static void main(@NonNull String @NonNull [] args) throws Exception {
        String addr = args.length > 0 ? args[0] : "tcp://127.0.0.1:5555";
        System.out.println("Connecting to " + addr + " ...");
        try (var t = new ProtocolClient(TcpConnection.connect(addr))) {
            var opening = t.receive();
            if (!(opening instanceof Frame.Welcome))
                throw new IllegalStateException("Protocol handshake failed: " + opening);

            // Test hint for /login (should return [user] [pass])
            Frame.HintResult r = t.hint("/login ", 5, TimeUnit.SECONDS);
            System.out.println("Hint '/login '      -> " + r);

            // Test hint for /pattern create (should return <name>)
            r = t.hint("/pattern create ", 5, TimeUnit.SECONDS);
            System.out.println("Hint '/pattern create ' -> " + r);

            // Test hint for /signup (should return [user] [pass])
            r = t.hint("/signup ", 5, TimeUnit.SECONDS);
            System.out.println("Hint '/signup '     -> " + r);

            // Test hint without trailing space (should return null/empty)
            r = t.hint("/login", 5, TimeUnit.SECONDS);
            System.out.println("Hint '/login'       -> " + r);

            // Test completion
            Frame.CompleteResult comp = t.complete("/log", 5, TimeUnit.SECONDS);
            System.out.println("Complete '/log'    -> " + comp);

            // Test /help
            t.send(new Frame.Request(t.nextSequence(), null, null, "/help"));
            Frame reply = t.receive();
            while (reply != null
                    && !(reply instanceof Frame.Done)
                    && !(reply instanceof Frame.Error)
                    && !(reply instanceof Frame.Terminate)) {
                reply = t.receive();
            }
            System.out.println("Request '/help'    -> " + reply);
        }
        System.out.println("\nDone.");
    }
}
