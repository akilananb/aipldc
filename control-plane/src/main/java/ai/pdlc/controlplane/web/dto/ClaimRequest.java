package ai.pdlc.controlplane.web.dto;

/** {@code POST /api/build-tasks/claim} body — {@code filters.profile} is the "project code"
 * (see {@code WorkItemRef.profile}); {@code filters.story}/{@code filters.task} narrow further. */
public record ClaimRequest(String agent, ClaimFilters filters) {

    public record ClaimFilters(String profile, String story, String task) {
    }
}
