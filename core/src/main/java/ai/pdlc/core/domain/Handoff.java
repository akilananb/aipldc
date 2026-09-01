package ai.pdlc.core.domain;

import java.util.List;

/**
 * The handoff envelope every agent output starts with — {@code docs/agent-playbook.md} lines 11-21.
 * Field names are wire literals (snake_case on the YAML/JSON wire via Jackson naming strategy applied
 * at the (de)serializer, camelCase in Java).
 *
 * @param from         who produced this, e.g. {@code grill-agent}
 * @param to           who consumes it, e.g. {@code po-agent}
 * @param workItem     the board id
 * @param state        the canonical state this transitions to
 * @param inputsRead   what the agent read, e.g. {@code ado:4412, repo:orders-service@a1b2c3}
 * @param confidence   agent's own estimate; humans read it, agents don't act on it
 * @param openQuestions must be empty to advance past a gate
 * @param blockedBy    ids of items that must close first
 */
public record Handoff(
        String from,
        String to,
        String workItem,
        CanonicalState state,
        List<String> inputsRead,
        double confidence,
        List<String> openQuestions,
        List<String> blockedBy) {

    public Handoff {
        inputsRead = inputsRead == null ? List.of() : List.copyOf(inputsRead);
        openQuestions = openQuestions == null ? List.of() : List.copyOf(openQuestions);
        blockedBy = blockedBy == null ? List.of() : List.copyOf(blockedBy);
    }
}
