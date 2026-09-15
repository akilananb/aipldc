package ai.pdlc.controlplane.demo;

/**
 * One signable release-pack document, mirroring {@code ai.pdlc.core.domain.ReleaseDocument}
 * ({@code docId} corresponds to {@code ReleaseDocument.id}). Signed status is derived at seed time
 * from the G3 gate's doc-id approvals, not stored here. {@code manifest.yaml} is metadata, not a
 * signable document, so it is excluded from {@code releaseDocuments}.
 */
public record DemoReleaseDocumentFixture(
        String docId,
        String title,
        String content,
        String checkerRole) {
}
