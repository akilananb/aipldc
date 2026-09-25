package ai.pdlc.core.workflow;

import io.temporal.activity.ActivityInterface;

/** Hosted by the agents worker on {@link TaskQueues#REASONING}; fetches the card with the connection's credential. */
@ActivityInterface
public interface A2aCardActivities {

    A2aCardWorkflow.CardResult readCard(String connectionId);
}
