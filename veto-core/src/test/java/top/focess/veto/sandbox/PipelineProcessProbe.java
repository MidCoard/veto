package top.focess.veto.sandbox;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.jspecify.annotations.NonNull;

/** Dependency-free commands used to exercise real pipeline pipes and exit ordering. */
public final class PipelineProcessProbe {
    private PipelineProcessProbe() {}

    public static void main(String @NonNull [] args) throws Exception {
        switch (args[0]) {
            case "producer" -> {
                System.out.println("ready");
                System.out.flush();
                Thread.sleep(1500);
            }
            case "noisy" -> {
                System.err.print("x".repeat(512 * 1024));
                System.err.flush();
                System.out.println("ready");
            }
            case "delayed-producer" -> {
                Thread.sleep(1000);
                System.out.println("ready");
            }
            case "consumer" ->
                    System.out.println(
                            new BufferedReader(new InputStreamReader(System.in)).readLine());
            case "slow-consumer" -> {
                System.out.println(new BufferedReader(new InputStreamReader(System.in)).readLine());
                Thread.sleep(1500);
            }
            default -> throw new IllegalArgumentException("unknown pipeline probe mode");
        }
    }
}
