package top.focess.veto.terminal;

import java.util.concurrent.TimeUnit;
import top.focess.veto.contract.IpcFrame;

/** Standalone hint protocol test — connects to a running backend and tests live hints. */
public class HintTest {
    public static void main(String[] args) throws Exception {
        String addr = args.length > 0 ? args[0] : "tcp://127.0.0.1:5555";
        System.out.println("Connecting to " + addr + " ...");
        ZmqClient t = new ZmqClient(addr);

        // Test hint for /login (should return [user] [pass])
        IpcFrame.HintResult r = t.hint("/login ", 5, TimeUnit.SECONDS);
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
        long seq = 1;
        t.send(new IpcFrame.Complete("/log", seq));
        IpcFrame reply = t.receive(seq, 5, TimeUnit.SECONDS);
        System.out.println("Complete '/log'    -> " + reply);

        // Test /help
        t.send(new IpcFrame.Request("/help"));
        reply = t.receive();
        while (!(reply instanceof IpcFrame.Done) && !(reply instanceof IpcFrame.Error)) {
            reply = t.receive();
        }
        System.out.println("Request '/help'    -> " + reply);

        t.close();
        System.out.println("\nDone.");
    }
}
