package ai.pdlc.core.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.List;

/**
 * Reads a remote A2A agent's card for the Studio (docs/phase-2-execution-spec.md slice 2.5). Like
 * {@link McpDiscoveryWorkflow} it runs in the agents worker, so the connection's credential is
 * never resolved by control-plane. A card's name, version and skills are not sensitive.
 */
@WorkflowInterface
public interface A2aCardWorkflow {

    @WorkflowMethod
    CardResult read(String connectionId);

    record Skill(String id, String name, String description) {
    }

    /** {@code error} is set (and the rest empty) when the card could not be read or is refused. */
    record CardResult(String name, String protocolVersion, boolean streaming, List<Skill> skills, String error) {
    }
}
