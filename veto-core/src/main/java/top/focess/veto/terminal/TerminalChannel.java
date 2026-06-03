package top.focess.veto.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.contract.TerminalRequest;
import top.focess.veto.contract.TerminalResponse;
import top.focess.veto.vault.CredentialVaultConfiguration;

/**
 * File-based IPC channel for the terminal. Watches {@code ~/.veto/terminal/in/} for incoming JSON
 * request files, dispatches via {@link CommandRegistry}, and writes structured {@link
 * TerminalResponse} JSON files to {@code ~/.veto/terminal/out/}.
 *
 * <p>No HTTP, no ports — just filesystem I/O. All command parsing and business logic lives in the
 * registry and its handlers.
 */
@Component
public class TerminalChannel {

    private static final Logger log = LoggerFactory.getLogger(TerminalChannel.class);

    private final ObjectMapper json = new ObjectMapper();
    private final CredentialVaultConfiguration config;
    private final CommandRegistry registry;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running;

    public TerminalChannel(CredentialVaultConfiguration config, CommandRegistry registry) {
        this.config = config;
        this.registry = registry;
    }

    @PostConstruct
    public void start() {
        running = true;
        executor.submit(this::watch);
        log.info("TerminalChannel: watching {}/terminal/in", config.getVaultHome());
    }

    @PreDestroy
    public void stop() {
        running = false;
        executor.shutdownNow();
        log.info("TerminalChannel: stopped");
    }

    private void watch() {
        Path inDir = Path.of(config.getVaultHome(), "terminal", "in");
        Path outDir = Path.of(config.getVaultHome(), "terminal", "out");
        try {
            Files.createDirectories(inDir);
            Files.createDirectories(outDir);
        } catch (IOException e) {
            log.error("TerminalChannel: cannot create directories", e);
            return;
        }

        while (running) {
            try (var stream = Files.newDirectoryStream(inDir, "*.json")) {
                for (Path file : stream) {
                    try {
                        String requestId = file.getFileName().toString().replace(".json", "");
                        TerminalRequest req = json.readValue(file.toFile(), TerminalRequest.class);
                        TerminalResponse resp = registry.dispatch(req.raw(), req.sessionToken());
                        Path respFile = outDir.resolve(requestId + ".json");
                        json.writeValue(respFile.toFile(), resp);
                        Files.delete(file);
                    } catch (Exception e) {
                        log.error("TerminalChannel: failed to process {}", file, e);
                        try {
                            Files.delete(file);
                        } catch (IOException ignored) {
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("TerminalChannel: scan failed", e);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                break;
            }
        }
    }
}
