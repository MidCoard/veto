package top.focess.veto.terminal;

import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import top.focess.veto.contract.IpcFrame;

/**
 * Minimal terminal test harness — connects via ZMQ and runs commands from stdin. Usage: {@code
 * gradle :veto-terminal:runTest}
 */
public class TerminalTestHarness {

    private static final String ADDR = "tcp://127.0.0.1:5555";

    public static void main(String[] args) throws Exception {
        System.out.println("Veto Terminal Test Harness");
        System.out.println("Connecting to " + ADDR + " ...");

        ZmqClient transport = new ZmqClient(ADDR);
        System.out.println("Connected! Type commands (/help, /login, /exit).");

        Scanner scanner = new Scanner(System.in);

        // Heartbeat thread
        Thread hb =
                new Thread(
                        () -> {
                            while (!Thread.currentThread().isInterrupted()) {
                                try {
                                    Thread.sleep(30_000);
                                    transport.send(new IpcFrame.Heartbeat());
                                } catch (InterruptedException e) {
                                    break;
                                }
                            }
                        },
                        "hb");
        hb.setDaemon(true);
        hb.start();

        while (true) {
            System.out.print("> ");
            System.out.flush();
            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;
            if (line.equals("/exit") || line.equals("/quit")) break;

            // Send request with per-seq routing
            transport.send(new IpcFrame.Request(line));

            // Print all response frames
            long deadline = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < deadline) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                IpcFrame.ServerFrame frame = transport.receive(remaining, TimeUnit.MILLISECONDS);
                if (frame == null) continue;

                switch (frame) {
                    case IpcFrame.Delta d -> System.out.print(d.content());
                    case IpcFrame.Progress p -> System.out.println("  ⏳ " + p.content());
                    case IpcFrame.Prompt prompt -> {
                        boolean mask = Boolean.TRUE.equals(prompt.meta().get("mask"));
                        System.out.print("  " + prompt.content() + " ");
                        System.out.flush();
                        String reply = scanner.nextLine().trim();
                        transport.send(new IpcFrame.Input(reply));
                        deadline = System.currentTimeMillis() + 120_000;
                    }
                    case IpcFrame.Done d -> {
                        System.out.println();
                        if (d.content() != null) System.out.println(d.content());
                        if (Boolean.TRUE.equals(d.meta().get("exit"))) {
                            transport.send(new IpcFrame.Bye());
                            transport.close();
                            return;
                        }
                        deadline = 0; // exit inner loop
                    }
                    case IpcFrame.Error e -> {
                        System.out.println("Error: " + e.content());
                        deadline = 0;
                    }
                    default -> {}
                }
                if (deadline == 0) break;
            }
        }
        transport.close();
        System.out.println("Goodbye.");
    }
}
