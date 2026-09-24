package ai.pdlc.controlplane.connections;

import ai.pdlc.core.workflow.A2aCardWorkflow;

/** Reads an A2A agent's card through the agents worker (which holds the credentials); {@link TemporalA2aCardClient} in production. */
public interface A2aCardClient {

    A2aCardWorkflow.CardResult read(String connectionId);
}
