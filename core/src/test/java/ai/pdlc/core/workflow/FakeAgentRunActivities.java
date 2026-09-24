package ai.pdlc.core.workflow;

import io.temporal.failure.ApplicationFailure;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** In-process {@link AgentRunActivities} for {@link AgentRunWorkflowImplTest}; behaviour is set per test. */
class FakeAgentRunActivities implements AgentRunActivities {

    enum Mode { SUCCEED, FAIL_ONCE_THEN_SUCCEED, ALWAYS_FAIL, REJECT, BLOCK, APPROVAL_THEN_SUCCEED, OPERATOR_THEN_SUCCEED }

    volatile Mode mode = Mode.SUCCEED;
    final AtomicInteger invocations = new AtomicInteger();
    final List<String> events = new CopyOnWriteArrayList<>();
    final CountDownLatch invoked = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch paused = new CountDownLatch(1);
    /** What {@link #escalateApproval} reports as the approval's status. */
    volatile String approvalStatusAtEscalation = "PENDING";

    @Override
    public AgentRunWorkflow.AgentRunOutcome invoke(String runId) {
        int attempt = invocations.incrementAndGet();
        invoked.countDown();
        switch (mode) {
            case FAIL_ONCE_THEN_SUCCEED -> {
                if (attempt == 1) {
                    throw ApplicationFailure.newFailure("provider returned 503", "ProviderError");
                }
            }
            case ALWAYS_FAIL -> throw ApplicationFailure.newFailure("provider returned 503", "ProviderError");
            case REJECT -> throw ApplicationFailure.newNonRetryableFailure("input \"input\" is required",
                    AgentRunActivities.NON_RETRYABLE);
            case BLOCK -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            case APPROVAL_THEN_SUCCEED -> {
                if (attempt == 1) {
                    events.add("awaiting:" + runId);
                    paused.countDown();
                    return AgentRunWorkflow.AgentRunOutcome.awaitingApproval(runId, "ap-1", 60, 1440);
                }
            }
            case OPERATOR_THEN_SUCCEED -> {
                if (attempt == 1) {
                    events.add("operator:" + runId);
                    paused.countDown();
                    return AgentRunWorkflow.AgentRunOutcome.needsOperator(runId, "ef-1");
                }
            }
            default -> { }
        }
        events.add("succeeded:" + runId);
        return new AgentRunWorkflow.AgentRunOutcome(runId, "SUCCEEDED", null);
    }

    @Override
    public void markFailed(String runId, String error) {
        events.add("failed:" + runId + ":" + error);
    }

    @Override
    public void markCancelled(String runId) {
        events.add("cancelled:" + runId);
    }

    @Override
    public String escalateApproval(String approvalId) {
        events.add("escalated:" + approvalId);
        return approvalStatusAtEscalation;
    }

    @Override
    public String expireApproval(String approvalId) {
        events.add("expired:" + approvalId);
        return "EXPIRED";
    }
}
