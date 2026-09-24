package ai.pdlc.controlplane.web.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Body of {@code GET/PUT /api/projects[/{id}]} — one Admin-managed project's full config. The
 * runtime view of a project is a {@code Profile} (document + deployment {@code agents}/{@code
 * notify}); this DTO is the DB-editable half only, matching {@code ProjectDocument}.
 */
public record ProjectDto(
        String id,
        String name,
        BoardDto board,
        List<RepoDto> repos,
        String confluenceUrl,
        List<DocLinkDto> docs,
        String brief,
        Map<String, GateDto> gates,
        BuildDto build,
        OffsetDateTime updatedAt,
        String updatedBy) {

    public record BoardDto(String provider, String org, String project, String authKind, String authSecretRef,
                           Map<String, String> types, Map<String, String> states) {
    }

    public record RepoDto(String id, String provider, String url, String defaultBranch, String specDir,
                          List<String> areas, boolean primary) {
    }

    public record DocLinkDto(String title, String url) {
    }

    public record GateDto(List<String> roles, boolean sod) {
    }

    public record BuildDto(String acpAgent) {
    }
}
