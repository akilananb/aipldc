package ai.pdlc.controlplane.web.dto;

import ai.pdlc.core.platform.ToolSpec;

/** Create ({@code id} required) or save a tool draft ({@code revision} = the revision it was based on). */
public record ToolDraftRequest(String id, String name, ToolSpec spec, Integer revision) {
}
