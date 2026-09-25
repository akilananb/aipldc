package ai.pdlc.agents.platform;

import ai.pdlc.adapters.a2a.A2aClient;
import ai.pdlc.adapters.a2a.A2aException;
import ai.pdlc.adapters.mcp.McpAuth;
import ai.pdlc.core.platform.EgressPolicy;
import ai.pdlc.core.workflow.A2aCardActivities;
import ai.pdlc.core.workflow.A2aCardWorkflow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Reads an A2A agent's card for the Studio (docs/phase-2-execution-spec.md slice 2.5) in the
 * process that holds credentials. Control-plane has checked the caller and the grant; this re-checks
 * that the connection is an active, unexpired {@code A2A_AGENT} before contacting it, and applies
 * the same card rules as a run (egress, same-origin endpoint, a supported version).
 */
@Component
public class A2aCardActivitiesImpl implements A2aCardActivities {

    static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final ToolStore tools;
    private final McpCredentials credentials;
    private final A2aClient client;
    private final Clock clock;

    @Autowired
    public A2aCardActivitiesImpl(ToolStore tools, McpCredentials credentials, EgressPolicy egress) {
        this(tools, credentials, new A2aClient(egress::check), Clock.systemUTC());
    }

    A2aCardActivitiesImpl(ToolStore tools, McpCredentials credentials, A2aClient client, Clock clock) {
        this.tools = tools;
        this.credentials = credentials;
        this.client = client;
        this.clock = clock;
    }

    @Override
    public A2aCardWorkflow.CardResult readCard(String connectionId) {
        ToolStore.ConnectionInfo c = tools.connection(connectionId).orElse(null);
        String problem = c == null ? "connection " + connectionId + " does not exist"
                : !"A2A_AGENT".equals(c.kind()) ? "connection " + connectionId + " is not an A2A_AGENT connection"
                : !"ACTIVE".equals(c.status()) ? "connection " + connectionId + " is revoked"
                : c.expiresAt() != null && !c.expiresAt().toInstant().isAfter(clock.instant()) ? "connection " + connectionId + " expired"
                : null;
        if (problem != null) {
            return error(problem);
        }
        try {
            McpAuth auth = "API_KEY".equals(c.authType())
                    ? credentials.auth(new McpCredentials.Connection(c.id(), c.authType(), c.secretRef(), c.baseUrl(), c.oauthClientId()))
                    : McpAuth.NONE;
            A2aClient.Card card = client.card(URI.create(c.baseUrl()), auth, TIMEOUT, A2aRunner.CARD_BYTES);
            return new A2aCardWorkflow.CardResult(card.name(), card.version(), card.streaming(),
                    card.skills().stream().map(s -> new A2aCardWorkflow.Skill(s.id(), s.name(), s.description())).toList(), null);
        } catch (IllegalStateException | A2aException e) {
            return error(e.getMessage());
        }
    }

    private static A2aCardWorkflow.CardResult error(String message) {
        return new A2aCardWorkflow.CardResult(null, null, false, List.of(), message);
    }
}
