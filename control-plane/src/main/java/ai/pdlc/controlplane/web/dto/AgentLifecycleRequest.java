package ai.pdlc.controlplane.web.dto;

/** {@code revision} for publish (the draft revision being published); {@code version} for rollback. */
public record AgentLifecycleRequest(Integer revision, Integer version) {
}
