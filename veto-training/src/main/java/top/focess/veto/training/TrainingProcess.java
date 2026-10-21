package top.focess.veto.training;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;

/** Owns one Python child and its output reader until both have retired. */
final class TrainingProcess {
    private TrainingProcess() {}

    static void run(
            @NonNull List<@NonNull String> command,
            @NonNull Path workingDirectory,
            long deadline,
            @NonNull Consumer<@NonNull String> output)
            throws IOException, InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException("Training cancelled before launch");
        if (System.nanoTime() >= deadline) throw new IOException("Training deadline exceeded");
        var builder =
                new ProcessBuilder(command)
                        .directory(workingDirectory.toFile())
                        .redirectErrorStream(true);
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        var process = builder.start();
        var drain =
                new FutureTask<Void>(
                        () -> {
                            try (var reader =
                                    new InputStreamReader(
                                            process.getInputStream(), StandardCharsets.UTF_8)) {
                                var line = new StringBuilder();
                                int value;
                                while ((value = reader.read()) != -1) {
                                    if (value == '\n' || value == '\r') {
                                        if (!line.isEmpty()) output.accept(line.toString());
                                        line.setLength(0);
                                    } else {
                                        if (line.length() >= 65536)
                                            throw new IOException(
                                                    "Training output line exceeds 65536 characters");
                                        line.append((char) value);
                                    }
                                }
                                if (!line.isEmpty()) output.accept(line.toString());
                            }
                            return null;
                        });
        var readerThread = Thread.ofVirtual().name("model-training-output").start(drain);
        var descendants = new HashSet<@NonNull ProcessHandle>();
        Throwable failure = null;
        try {
            while (process.isAlive()) {
                descendants.addAll(process.descendants().toList());
                if (process.waitFor(100, TimeUnit.MILLISECONDS)) break;
                if (drain.isDone()) drain.get();
                if (System.nanoTime() >= deadline)
                    throw new IOException("Training deadline exceeded");
            }
            drain.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (process.exitValue() != 0)
                throw new IOException(
                        "Training subprocess exited with code " + process.exitValue());
        } catch (ExecutionException | TimeoutException e) {
            var error = new IOException("Training output failed or timed out", e);
            failure = error;
            throw error;
        } catch (Throwable e) {
            failure = e;
            throw e;
        } finally {
            try {
                Throwable retirementFailure = null;
                try {
                    descendants.addAll(process.descendants().toList());
                    boolean refused = false;
                    for (var child : descendants) {
                        if (child.isAlive() && !child.destroyForcibly() && child.isAlive())
                            refused = true;
                    }
                    var retired = process.isAlive() ? process.destroyForcibly() : process;
                    if (!retired.waitFor(5, TimeUnit.SECONDS))
                        throw new IOException("Training child did not retire");
                    if (refused)
                        throw new IOException("A training descendant could not be stopped");
                    for (var child : descendants) {
                        try {
                            child.onExit().get(5, TimeUnit.SECONDS);
                        } catch (ExecutionException | TimeoutException e) {
                            throw new IOException("Training descendant did not retire", e);
                        }
                    }
                } catch (Throwable e) {
                    retirementFailure = e;
                    throw e;
                } finally {
                    try {
                        drain.cancel(true);
                        readerThread.interrupt();
                        process.getInputStream().close();
                        readerThread.join(1000);
                        if (readerThread.isAlive())
                            throw new IOException("Training output reader did not retire");
                    } catch (Throwable cleanup) {
                        if (retirementFailure != null) retirementFailure.addSuppressed(cleanup);
                        else throw cleanup;
                    }
                }
            } catch (Throwable cleanup) {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
    }
}
