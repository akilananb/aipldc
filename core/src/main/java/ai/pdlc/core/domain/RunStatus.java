package ai.pdlc.core.domain;

/** @param state {@code queued|running|succeeded|failed} */
public record RunStatus(String state, boolean success) {
}
