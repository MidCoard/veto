package top.focess.veto.terminal;

import top.focess.veto.contract.IpcFrame;

/** Standalone hint protocol test — connects to a running backend and tests live hints. */
public class HintTest {
    public static void main(String[] args) throws Exception {
        String addr = args.length > 0 ? args[0] : "tcp://127.0.0.1:5555";
        System.out.println("Connecting to " + addr + " ...");
        ZmqTerminal t = new ZmqTerminal(addr);

        // Test hint for /login (should return [user] [pass])
        t.send(new IpcFrame.Hint("/login "));
        IpcFrame r = t.receive();
        System.out.println("Hint '/login '      -> " + r);

        // Test hint for /pattern create (should return <name>)
        t.send(new IpcFrame.Hint("/pattern create "));
        r = t.receive();
        System.out.println("Hint '/pattern create ' -> " + r);

        // Test hint for /signup (should return [user] [pass])
        t.send(new IpcFrame.Hint("/signup "));
        r = t.receive();
        System.out.println("Hint '/signup '     -> " + r);

        // Test hint without trailing space (should return null/empty)
        t.send(new IpcFrame.Hint("/login"));
        r = t.receive();
        System.out.println("Hint '/login'       -> " + r);

        // Test completion
        t.send(new IpcFrame.Complete("/log"));
        r = t.receive();
        System.out.println("Complete '/log'    -> " + r);

        // Test /help
        t.send(new IpcFrame.Request("/help"));
        r = t.receive();
        while (!(r instanceof IpcFrame.Done) && !(r instanceof IpcFrame.Error)) {
            r = t.receive();
        }
        System.out.println("Request '/help'    -> " + r);

        t.close();
        System.out.println("\nDone.");
    }
}
