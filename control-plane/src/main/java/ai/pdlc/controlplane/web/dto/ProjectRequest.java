package ai.pdlc.controlplane.web.dto;

import java.util.List;
import java.util.Map;

/** Body of {@code PUT /api/projects/{id}} — the same shape as {@link ProjectDto} minus the
 * server-owned fields ({@code id} is the path variable; {@code updatedAt}/{@code updatedBy} come
 * from the {@code projects} row). */
public record ProjectRequest(
        String name,
        ProjectDto.BoardDto board,
        List<ProjectDto.RepoDto> repos,
        String confluenceUrl,
        List<ProjectDto.DocLinkDto> docs,
        String brief,
        Map<String, ProjectDto.GateDto> gates,
        ProjectDto.BuildDto build) {
}
