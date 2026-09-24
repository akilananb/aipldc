package ai.pdlc.core.workflow;

import io.temporal.activity.ActivityInterface;

/** Hosted by the agents worker on {@link TaskQueues#REASONING}; resolves the connection's credential and lists tools. */
@ActivityInterface
public interface McpDiscoveryActivities {

    McpDiscoveryWorkflow.DiscoveryResult listTools(String connectionId);
}
