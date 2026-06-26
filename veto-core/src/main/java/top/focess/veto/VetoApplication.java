package top.focess.veto;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

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

    public static void main(String[] args) {
        SpringApplication.run(VetoApplication.class, args);
    }
}
