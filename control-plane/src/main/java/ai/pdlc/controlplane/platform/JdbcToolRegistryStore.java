package ai.pdlc.controlplane.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcToolRegistryStore extends JdbcDefinitionStore implements ToolRegistryStore {

    public JdbcToolRegistryStore(JdbcTemplate jdbc) {
        super(jdbc, "tool");
    }
}
