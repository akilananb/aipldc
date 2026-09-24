package ai.pdlc.controlplane.web.dto;

/**
 * What a new run pins: the immutable agent version plus the model it will actually call right now
 * ({@code fallback} = true when the bound model was unavailable and a declared fallback was chosen).
 */
public record ResolvedAgentDto(AgentVersionDto definition, String model, String providerModel, String connectionId,
                               boolean fallback) {
}
