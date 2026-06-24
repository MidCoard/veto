package top.focess.veto;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Project Veto - Zero-Trust Cloud-Edge Agent Client.
 *
 * <p>Entry point for the core engine hosting components bus - observability: bus: Communication &
 * Routing Bus mcp: MCP Extensibility Engine orchestrator: Swarm Lifecycle Orchestrator sandbox:
 * Atomic Tool Execution Sandbox gateway: Local SLM Veto Gateway vault: Local Credential Vault
 * observability: Observability & Shadow Audit
 */
@SpringBootApplication
@EnableScheduling
public class VetoApplication {

    public static void main(String[] args) {
        SpringApplication.run(VetoApplication.class, args);
    }
}
