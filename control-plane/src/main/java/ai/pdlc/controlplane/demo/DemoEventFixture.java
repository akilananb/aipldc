package ai.pdlc.controlplane.demo;

import java.time.Instant;
import java.util.Map;

/**
 * One review.md trail event, later fed to {@code ReviewMdWriter}. {@code kind} selects the writer
 * method and {@code payload} carries that method's parameters. Timestamps start at
 * {@code 2026-09-14T10:00:00Z} and strictly increase by at least one minute per event across the
 * whole bundle (never {@code now()}).
 *
 * <p>Kind → {@code ReviewMdWriter} method → payload keys:
 * <ul>
 *   <li>{@code draft} → {@code draftBlock}: {@code version, investSummary, dorUnmetSummary}</li>
 *   <li>{@code revision} → {@code revisionBlock}: {@code version, changeLines[], investSummary, dorSummary, resolvedRefs[]}</li>
 *   <li>{@code comment} → {@code commentBlock}: {@code role, target, text}</li>
 *   <li>{@code quality} → {@code qualityBlock}: {@code version, verdict, score}</li>
 *   <li>{@code approval} → {@code approveBlock}: {@code role, version, note}</li>
 *   <li>{@code gate} → {@code gatePassedBlock}: {@code gate, version}</li>
 *   <li>{@code pr} → {@code prOpenedBlock}: {@code taskCount, branch, target, prUrl, taskLines[], findingLines[]}</li>
 *   <li>{@code release} → {@code releasePackBlock}: {@code releaseId, documentLines[]}</li>
 *   <li>{@code sign} → {@code documentSignedBlock}: {@code role, docId}</li>
 *   <li>{@code deploy} → {@code deployedBlock}: {@code env, releaseId, branch, runId}</li>
 *   <li>{@code monitor} → {@code monitorEvaluationBlock}: {@code tripLines[]}</li>
 *   <li>{@code agentResult} → {@code agentResultBlock}: {@code agentName, target, approvedBy, markdown}</li>
 * </ul>
 */
public record DemoEventFixture(
        String kind,
        Map<String, Object> payload,
        Instant timestamp) {
}
