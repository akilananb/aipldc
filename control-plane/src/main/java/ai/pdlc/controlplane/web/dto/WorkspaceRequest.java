package ai.pdlc.controlplane.web.dto;

import java.util.List;

/** Create a workspace; {@code admins} receive {@code WORKSPACE_ADMIN} so someone can manage it. */
public record WorkspaceRequest(String id, String name, List<String> admins) {
}
