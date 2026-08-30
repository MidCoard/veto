package top.focess.veto.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.NonNull;

/** Dependency-free child used to test the production AppContainer boundary. */
public final class WindowsSandboxEscapeProbe {

    private WindowsSandboxEscapeProbe() {}

    public static void main(String @NonNull [] args) {
        if (args.length != 3) {
            System.err.println("expected inside-path outside-path secret-path");
            System.exit(2);
        }
        tryWrite("inside-write", Path.of(args[0]));
        tryWrite("outside-write", Path.of(args[1]));
        tryRead(Path.of(args[2]));
        System.out.println("temp=" + System.getenv("TEMP"));
        System.out.println("tmp=" + System.getenv("TMP"));
        System.out.println("tmpdir=" + System.getenv("TMPDIR"));
    }

    private static void tryWrite(@NonNull String label, @NonNull Path path) {
        try {
            Files.writeString(path, label);
            System.out.println(label + "=allowed");
        } catch (IOException denied) {
            System.out.println(label + "=denied");
        }
    }

    private static void tryRead(@NonNull Path path) {
        try {
            Files.readString(path);
            System.out.println("outside-read=allowed");
        } catch (IOException denied) {
            System.out.println("outside-read=denied");
        }
    }
}
