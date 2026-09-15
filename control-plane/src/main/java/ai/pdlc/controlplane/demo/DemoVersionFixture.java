package ai.pdlc.controlplane.demo;

import java.util.List;
import java.util.Map;

/**
 * One artifact version of a story: {@code files} maps a change-folder-relative path to its UTF-8
 * content; {@code comments}/{@code approvals} mirror the durable per-version spec records.
 */
public record DemoVersionFixture(
        int version,
        Map<String, String> files,
        List<DemoCommentFixture> comments,
        List<DemoApprovalFixture> approvals) {
}
