package ai.pdlc.controlplane.web.dto;

import ai.pdlc.core.platform.AgentSpec;

/**
 * Create ({@code id} required, {@code revision} ignored) or save a draft ({@code id} from the
 * path, {@code revision} = the draft revision the edit was based on).
 */
public record AgentDraftRequest(String id, String name, AgentSpec spec, Integer revision) {
}
