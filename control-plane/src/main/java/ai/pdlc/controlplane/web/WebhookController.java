package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.persistence.IngestedEventStore;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.domain.CanonicalEvent;
import ai.pdlc.core.domain.WorkItem;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.BoardPort;
import ai.pdlc.core.workflow.BoardCommentEvent;
import ai.pdlc.core.workflow.FeatureWorkflow;
import ai.pdlc.core.workflow.TaskQueues;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Webhook ingress — plan step 4. {@code POST /webhooks/ado} accepts ADO service-hook payloads;
 * {@code POST /webhooks/local} accepts the same shape for the in-memory profile (tests/seed
 * script). Idempotent by {@code item+rev} (orchestration-decision §5): {@code item.created} with
 * kind=feature starts {@link FeatureWorkflow} with {@code WorkflowIdReusePolicy.REJECT_DUPLICATE}
 * so replays no-op; {@code comment.added} signals {@code commentAdded}.
 */
@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private final BoardPort board;
    private final IngestedEventStore ingestedEvents;
    private final WorkflowClient workflowClient;
    private final Profile activeProfile;

    public WebhookController(BoardPort board, IngestedEventStore ingestedEvents, WorkflowClient workflowClient, Profile activeProfile) {
        this.board = board;
        this.ingestedEvents = ingestedEvents;
        this.workflowClient = workflowClient;
        this.activeProfile = activeProfile;
    }

    @PostMapping("/local")
    public ResponseEntity<Map<String, String>> local(@RequestBody Map<String, Object> payload) {
        return handle(payload);
    }

    @PostMapping("/ado")
    public ResponseEntity<Map<String, String>> ado(@RequestBody Map<String, Object> payload) {
        return handle(payload);
    }

    private ResponseEntity<Map<String, String>> handle(Map<String, Object> payload) {
        CanonicalEvent event = board.onWebhook(activeProfile.name(), payload);
        boolean isNew = ingestedEvents.recordIfNew(
                event.itemRef().profile(), event.itemRef().boardId(), event.rev(), event.kind().wireValue());
        if (!isNew) {
            return ResponseEntity.ok(Map.of("status", "duplicate-ignored"));
        }

        switch (event.kind()) {
            case ITEM_CREATED -> {
                WorkItem item = board.getItem(event.itemRef());
                if ("feature".equals(item.kind())) {
                    startFeatureWorkflow(event.itemRef());
                }
            }
            case COMMENT_ADDED -> signalCommentAdded(event.itemRef(), payload);
            case ITEM_UPDATED -> {
                // No workflow reaction needed for plain field edits in the pilot.
            }
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    private void startFeatureWorkflow(WorkItemRef itemRef) {
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.REASONING)
                .setWorkflowId(itemRef.workflowId())
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build();
        FeatureWorkflow stub = workflowClient.newWorkflowStub(FeatureWorkflow.class, options);
        try {
            WorkflowClient.start(stub::run, itemRef);
        } catch (WorkflowExecutionAlreadyStarted alreadyStarted) {
            // Idempotent: a webhook replay (or reconciler safety-net event) for the same item is a no-op.
        }
    }

    private void signalCommentAdded(WorkItemRef itemRef, Map<String, Object> payload) {
        String author = String.valueOf(payload.getOrDefault("author", ""));
        String text = String.valueOf(payload.getOrDefault("text", ""));
        String commentId = itemRef.boardId() + "-webhook-" + System.nanoTime();
        FeatureWorkflow stub = workflowClient.newWorkflowStub(FeatureWorkflow.class, itemRef.workflowId());
        try {
            stub.commentAdded(new BoardCommentEvent(commentId, author, text));
        } catch (RuntimeException noRunningWorkflow) {
            // Comment landed on an item with no running FeatureWorkflow (e.g. after gate 1) - ignore.
        }
    }
}
