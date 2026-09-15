package ai.pdlc.controlplane.demo;

import ai.pdlc.core.domain.CanonicalState;

import java.util.List;

/**
 * One board work item in a snapshot group. {@code kind} is {@code feature|story|task|bug|release};
 * {@code release} is a fixture/UI-level kind for the two release child cards ({@code demo-10-release},
 * {@code demo-11-release}) — the canonical {@code WorkItem} kind set is {@code feature|story|task|bug}.
 *
 * <p>Empty collections are {@code []}; {@code parentBoardId}/{@code specChangePath}/{@code gate}/
 * {@code buildEvidence} are {@code null} when absent. Task items never carry {@code versions},
 * {@code qualityReports} or {@code gate} (tasks have no spec artifact of their own and no quality
 * report). Story items carry the full artifact/quality/gate/build-evidence/release surface.
 */
public record DemoWorkItemFixture(
        String boardId,
        String kind,
        String title,
        String description,
        String parentBoardId,
        CanonicalState state,
        String specChangePath,
        List<DemoVersionFixture> versions,
        DemoGateFixture gate,
        DemoGrillFixture grill,
        List<DemoCommentFixture> boardComments,
        List<DemoQualityReportFixture> qualityReports,
        List<DemoReleaseDocumentFixture> releaseDocuments,
        List<DemoEventFixture> events,
        DemoBuildEvidence buildEvidence) {
}
