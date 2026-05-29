package top.focess.veto;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Project Veto  - Zero-Trust Cloud-Edge Agent Client.
 *
 * Entry point for the core engine hosting components C3 - C9:
 *   C3: Communication & Routing Bus
 *   C4: MCP Extensibility Engine
 *   C5: Swarm Lifecycle Orchestrator
 *   C6: Atomic Tool Execution Sandbox
 *   C7: Local SLM Veto Gateway
 *   C8: Local Credential Vault
 *   C9: Observability & Shadow Audit
 */
@SpringBootApplication
@EnableScheduling
public class VetoApplication {

    public static void main(String[] args) {
        SpringApplication.run(VetoApplication.class, args);
    }
}
