package ai.pdlc.controlplane.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAgentRegistryStore extends JdbcDefinitionStore implements AgentRegistryStore {

    public JdbcAgentRegistryStore(JdbcTemplate jdbc) {
        super(jdbc, "agent");
    }
}
