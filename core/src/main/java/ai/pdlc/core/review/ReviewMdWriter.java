package ai.pdlc.core.review;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Pure formatter for {@code review.md} blocks, matching {@code docs/agent-playbook.md} lines
 * 520-544 exactly. No I/O: callers (control-plane's {@code BoardSideEffects}) read the current file
 * via {@code RepoPort.readFile}, append a block here, and write it back via {@code RepoPort.writeFiles}.
 */
public final class ReviewMdWriter {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private ReviewMdWriter() {
    }

    /** {@code # review.md — <changePath>} header for a brand-new file. {@code changePath} is the
     * full change-folder path, e.g. {@code openspec/changes/export-orders-csv} (playbook lines
     * 520-544 - the literal shown there is just the worked example's own path). */
    public static String header(String changePath) {
        return "# review.md — " + changePath + "\n";
    }

    /** {@code ## vN · PO agent · <ts>} followed by the one-line drafted summary. */
    public static String draftBlock(int version, OffsetDateTime at, String investSummary, String dorUnmetSummary) {
        return "\n## v%d · PO agent · %s\nstory drafted · %s · DoR unmet: %s\n"
                .formatted(version, TS.format(at), investSummary, dorUnmetSummary);
    }

    /** {@code ### comment · <role> · <target> · <hh:mm>} followed by the comment text. */
    public static String commentBlock(String role, String targetDescription, OffsetDateTime at, String text) {
        return "\n### comment · %s · %s · %s\n%s\n".formatted(role, targetDescription, HM.format(at), text);
    }

    /** {@code → request changes · <role> · <hh:mm>} */
    public static String requestChangesBlock(String role, OffsetDateTime at) {
        return "\n→ request changes · %s · %s\n".formatted(role, HM.format(at));
    }

    /** {@code ## vN · PO agent · <ts>} revision block with change lines, INVEST/DoR re-run, replies. */
    public static String revisionBlock(
            int version,
            OffsetDateTime at,
            List<String> changeLines,
            String investSummary,
            String dorSummary,
            List<String> resolvedRefs) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## v").append(version).append(" · PO agent · ").append(TS.format(at)).append('\n');
        for (String line : changeLines) {
            sb.append("- ").append(line).append('\n');
        }
        sb.append("- INVEST re-run: ").append(investSummary).append(" · DoR: ").append(dorSummary).append('\n');
        if (!resolvedRefs.isEmpty()) {
            sb.append("- replies: ").append(String.join(" · ", resolvedRefs)).append('\n');
        }
        return sb.toString();
    }

    /** {@code ### approve · <role> · vN · <hh:mm> · "<note>"} */
    public static String approveBlock(String role, int version, OffsetDateTime at, String note) {
        return "\n### approve · %s · v%d · %s · \"%s\"\n".formatted(role, version, HM.format(at), note);
    }

    /** {@code → gate <n> passed on v<version> · state: Approved} */
    public static String gatePassedBlock(int gate, int version) {
        return "\n→ gate %d passed on v%d · state: Approved\n".formatted(gate, version);
    }

    /** {@code ### quality · vN · <verdict> · score <score> · <hh:mm>} — quality agent verdict on a
     * story draft version (playbook: quality agent hard-blocks gate 1). */
    public static String qualityBlock(int version, String verdict, int score, OffsetDateTime at) {
        return "\n### quality · v%d · %s · score %d · %s\n".formatted(version, verdict, score, HM.format(at));
    }

    /** {@code ### agent-analysis · @<agent> · <target> · <hh:mm> · approved by <who>} followed by
     * the @-mentioned agent's approved markdown draft. */
    public static String agentResultBlock(String agentName, String targetDescription, OffsetDateTime at,
                                          String approvedBy, String markdown) {
        return "\n### agent-analysis · @%s · %s · %s · approved by %s\n%s\n"
                .formatted(agentName, targetDescription, HM.format(at), approvedBy, markdown);
    }

    /** {@code ## PR opened · branch -> target} with per-task build results and review findings —
     * build-order phase 3, before gate 2. */
    public static String prOpenedBlock(int taskCount, String branch, String target, String prUrl,
                                        List<String> taskLines, List<String> findingLines) {
        StringBuilder sb = new StringBuilder("\n## PR opened · ").append(branch).append(" -> ").append(target).append('\n');
        sb.append("url: ").append(prUrl).append('\n');
        sb.append("tasks: ").append(taskCount).append('\n');
        for (String line : taskLines) {
            sb.append("- ").append(line).append('\n');
        }
        if (!findingLines.isEmpty()) {
            sb.append("findings:\n");
            for (String line : findingLines) {
                sb.append("- ").append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** {@code ## Release pack published · <releaseId>} listing each document's checker role -
     * playbook §7 "Produces", tech-stack §3.4 "a document-level sign is a signal with doc_id". */
    public static String releasePackBlock(String releaseId, List<String> documentLines) {
        StringBuilder sb = new StringBuilder("\n## Release pack published · ").append(releaseId).append('\n');
        for (String line : documentLines) {
            sb.append("- ").append(line).append('\n');
        }
        return sb.toString();
    }

    /** {@code ### signed · <role> · <docId> · <hh:mm>} — one line per document signature. */
    public static String documentSignedBlock(String role, String docId, OffsetDateTime at) {
        return "\n### signed · %s · %s · %s\n".formatted(role, docId, HM.format(at));
    }

    /** {@code ## Deployed · <env> · <releaseId>} — playbook §7 step 6, only ever after gate 3. */
    public static String deployedBlock(String env, String releaseId, String branch, String runId) {
        return "\n## Deployed · %s · %s\nbranch: %s\nrun: %s\n".formatted(env, releaseId, branch, runId);
    }

    /** {@code ## Monitor evaluation · N trip(s)} — playbook §8 "Produces", empty when nothing tripped. */
    public static String monitorEvaluationBlock(List<String> tripLines) {
        StringBuilder sb = new StringBuilder("\n## Monitor evaluation · ").append(tripLines.size()).append(" trip(s)\n");
        for (String line : tripLines) {
            sb.append("- ").append(line).append('\n');
        }
        return sb.toString();
    }

    public static String append(String existing, String block) {
        String base = existing == null || existing.isEmpty()
                ? ""
                : (existing.endsWith("\n") ? existing : existing + "\n");
        return base + block;
    }
}
