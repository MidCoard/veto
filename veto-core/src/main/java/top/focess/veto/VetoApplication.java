package top.focess.veto;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.sandbox.SandboxBootstrap;

/**
 * Project Veto — Zero-Trust Cloud-Edge Agent Client.
 *
 * <p>Core engine components: {@code bus} — Communication &amp; Routing Bus · {@code mcp} — MCP
 * Extensibility Engine · {@code sandbox} — Atomic Tool Execution Sandbox · {@code gateway} — Local
 * SLM Veto Gateway · {@code vault} — Local Credential Vault · {@code observability} — Observability
 * &amp; Shadow Audit.
 */
@SpringBootApplication
@EnableScheduling
public class VetoApplication {

    public static void main(@NonNull String @NonNull [] args) {
        if (SandboxBootstrap.isInvocation(args)) {
            System.exit(SandboxBootstrap.run(args));
            return;
        }
        SpringApplication.run(ToolDocs.nonNullClass(VetoApplication.class), args);
    }
}
