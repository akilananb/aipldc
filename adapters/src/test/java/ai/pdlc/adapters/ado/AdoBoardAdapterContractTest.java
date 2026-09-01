package ai.pdlc.adapters.ado;

import ai.pdlc.adapters.BoardPortContractTest;
import ai.pdlc.core.port.BoardPort;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Map;

/**
 * Runs the {@link BoardPortContractTest} suite against a real Azure DevOps org and project only
 * when {@code ADO_ORG}, {@code ADO_PROJECT} and {@code ADO_PAT} are all set (plan Verification
 * step 4 "Optional real-provider check"). Point {@code ADO_PROJECT} at a scratch area path; this
 * test creates and mutates real work items.
 */
@EnabledIfEnvironmentVariable(named = "ADO_ORG", matches = ".+")
@EnabledIfEnvironmentVariable(named = "ADO_PROJECT", matches = ".+")
@EnabledIfEnvironmentVariable(named = "ADO_PAT", matches = ".+")
class AdoBoardAdapterContractTest extends BoardPortContractTest {

    private static final Map<String, String> STATES = Map.of(
            "new", "New",
            "ready-for-story", "Ready for Story");

    private static final Map<String, String> TYPES = Map.of("feature", "Feature");

    @Override
    protected BoardPort port() {
        return new AdoBoardAdapter(
                System.getenv("ADO_ORG"),
                System.getenv("ADO_PROJECT"),
                System.getenv("ADO_PAT"),
                STATES,
                TYPES);
    }

    @Override
    protected String profile() {
        return "ado-pilot";
    }
}
