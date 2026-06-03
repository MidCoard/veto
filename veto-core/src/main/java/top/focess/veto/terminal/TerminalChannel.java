package top.focess.veto.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.TerminalIO;
import top.focess.veto.contract.TerminalRequest;
import top.focess.veto.contract.TerminalResponse;
import top.focess.veto.vault.CredentialVaultConfiguration;

@Component
public class TerminalChannel {

    private static final Logger log = LoggerFactory.getLogger(TerminalChannel.class);
    private final ObjectMapper json = new ObjectMapper();
    private final CredentialVaultConfiguration config;
    private final CommandRegistry registry;
    private final Map<String, TerminalIO> pending = new ConcurrentHashMap<>();
    private volatile boolean running;

    public TerminalChannel(CredentialVaultConfiguration config, CommandRegistry registry) {
        this.config = config;
        this.registry = registry;
    }

    @PostConstruct
    public void start() {
        running = true;
        Thread.ofPlatform().daemon().name("terminal-channel").start(this::watch);
    }

    @PreDestroy
    public void stop() {
        running = false;
    }

    private void watch() {
        Path inDir = Path.of(config.getVaultHome(), "terminal", "in");
        Path outDir = Path.of(config.getVaultHome(), "terminal", "out");
        try {
            Files.createDirectories(inDir);
            Files.createDirectories(outDir);
        } catch (IOException e) {
            log.error("Cannot create dirs", e);
            return;
        }

        while (running) {
            try (var stream = Files.newDirectoryStream(inDir, "*.json")) {
                for (Path file : stream) {
                    String filename = file.getFileName().toString();

                    if (filename.contains("-next")) {
                        String requestId = filename.replace("-next.json", "");
                        TerminalIO io = pending.get(requestId);
                        if (io != null && io.hasInput()) {
                            try {
                                TerminalRequest fu =
                                        json.readValue(file.toFile(), TerminalRequest.class);
                                Files.delete(file);
                                log.debug("Feeding input to {}", requestId);
                                io.input(fu.raw().trim());
                            } catch (Exception e) {
                                log.error("Feed failed", e);
                                try {
                                    Files.deleteIfExists(file);
                                } catch (IOException ignored) {
                                }
                            }
                        }
                        continue;
                    }

                    String requestId = filename.replace(".json", "");
                    try {
                        TerminalRequest req = json.readValue(file.toFile(), TerminalRequest.class);
                        Files.delete(file);

                        TerminalIO io = new TerminalIO(outDir, requestId);
                        pending.put(requestId, io);

                        // Dispatch on a virtual thread so watch() can keep polling
                        // while commands block on io.input()
                        Thread.ofVirtual()
                                .name("cmd-" + requestId.substring(0, 8))
                                .start(
                                        () -> {
                                            try {
                                                TerminalResponse resp =
                                                        registry.dispatch(
                                                                req.raw(), req.sessionToken(), io);
                                                Path outFile = outDir.resolve(requestId + ".json");
                                                if (!Files.exists(outFile)) {
                                                    json.writeValue(outFile.toFile(), resp);
                                                }
                                            } catch (Exception e) {
                                                log.error("Dispatch failed for {}", requestId, e);
                                            } finally {
                                                pending.remove(requestId);
                                            }
                                        });
                    } catch (Exception e) {
                        log.error("Failed processing {}", file, e);
                        try {
                            Files.deleteIfExists(file);
                        } catch (IOException ignored) {
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Scan failed", e);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                break;
            }
        }
    }
}
