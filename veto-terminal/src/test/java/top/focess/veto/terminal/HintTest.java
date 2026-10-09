package top.focess.veto.terminal;

import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.ProtocolClient;
import top.focess.veto.transport.zmq.ZmqChannel;

/** Standalone hint protocol test — connects to a running backend and tests live hints. */
public class HintTest {
    public static void main(@NonNull String @NonNull [] args) throws Exception {
        String addr = args.length > 0 ? args[0] : "tcp://127.0.0.1:5555";
        System.out.println("Connecting to " + addr + " ...");
        ProtocolClient t = new ProtocolClient(ZmqChannel.Client.connect(addr));

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
        t.send(new Frame.Request("/help"));
        Frame reply = t.receive();
        while (!(reply instanceof Frame.Done) && !(reply instanceof Frame.Error)) {
            reply = t.receive();
        }
        System.out.println("Request '/help'    -> " + reply);

        t.close();
        System.out.println("\nDone.");
    }
}
