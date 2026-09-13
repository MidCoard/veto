package top.focess.veto.model;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Hibernate update does not remove retired columns; no values are copied into replacement state.
 */
@Component
public class RetiredAgentControls implements ApplicationRunner {
    private final @NonNull JdbcTemplate jdbc;

    public RetiredAgentControls(@NonNull JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(@NonNull ApplicationArguments args) {
        jdbc.execute("ALTER TABLE agent_instances DROP COLUMN IF EXISTS user_paused");
        jdbc.execute("ALTER TABLE agent_instances DROP COLUMN IF EXISTS execution_wait");
        jdbc.execute("ALTER TABLE agent_instances DROP COLUMN IF EXISTS wait_request_id");
    }
}
