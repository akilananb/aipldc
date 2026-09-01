package ai.pdlc.controlplane.web;

import ai.pdlc.adapters.inmemory.InMemoryBoardAdapter;
import ai.pdlc.controlplane.persistence.IngestedEventStore;
import ai.pdlc.core.config.AgentsConfig;
import ai.pdlc.core.config.BoardConfig;
import ai.pdlc.core.config.GateConfig;
import ai.pdlc.core.config.NotifyConfig;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.RepoConfig;
import ai.pdlc.core.domain.Approval;
import ai.pdlc.core.domain.Comment;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.workflow.BoardCommentEvent;
import ai.pdlc.core.workflow.FeatureWorkflow;
import ai.pdlc.core.workflow.ReviewState;
import ai.pdlc.core.workflow.TaskQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercises the real webhook-ingress-to-workflow wiring against a {@link TestWorkflowEnvironment}
 * (no mocked Temporal client) plus a real {@link InMemoryBoardAdapter}. */
class WebhookControllerTest {

    private TestWorkflowEnvironment testEnv;

    /** Records signals instead of running the real gate-1 logic - this test is about ingress
     * wiring (start/dedupe/signal), not workflow behaviour (covered in core's replay tests). */
    public static class RecordingWorkflowImpl implements FeatureWorkflow {
        static final List<BoardCommentEvent> COMMENTS = new CopyOnWriteArrayList<>();

        @Override
        public void run(WorkItemRef item) {
            Workflow.await(() -> false); // block forever; test only checks start/signal delivery
        }

        @Override
        public void comment(Comment c) {
        }

        @Override
        public void approve(Approval a) {
        }

        @Override
        public void requestChanges(String by) {
        }

        @Override
        public void commentAdded(BoardCommentEvent e) {
            COMMENTS.add(e);
        }

        @Override
        public ReviewState state() {
            return new ReviewState(1, Map.of(), List.of(), ai.pdlc.core.domain.CanonicalState.NEW);
        }
    }

    private WebhookController newController(WorkflowClient client, InMemoryBoardAdapter board) {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        IngestedEventStore store = new IngestedEventStore(jdbcTemplate);
        Profile profile = new Profile("local",
                new BoardConfig("in-memory", "local", "PDLC", Map.of(), Map.of(), new BoardConfig.AuthConfig("none", "kv://none")),
                new RepoConfig("in-memory", "local://x", "main", "openspec"),
                new NotifyConfig("none", "none"),
                new AgentsConfig("http://stub", Map.of()),
                Map.of("G1", new GateConfig(List.of("PO", "SquadLead"), true)));
        return new WebhookController(board, store, client, profile);
    }

    @AfterEach
    void tearDown() {
        RecordingWorkflowImpl.COMMENTS.clear();
        if (testEnv != null) {
            testEnv.close();
        }
    }

    @Test
    void itemCreatedForFeatureStartsFeatureWorkflow() throws Exception {
        testEnv = TestWorkflowEnvironment.newInstance();
        Worker worker = testEnv.newWorker(TaskQueues.REASONING);
        worker.registerWorkflowImplementationTypes(RecordingWorkflowImpl.class);
        testEnv.start();

        InMemoryBoardAdapter board = new InMemoryBoardAdapter();
        WebhookController controller = newController(testEnv.getWorkflowClient(), board);

        controller.local(Map.of("kind", "item.created", "boardId", "4412", "rev", 1,
                "itemKind", "feature", "title", "Export the filtered orders view to CSV"));

        // Workflow started + running: a query should succeed without throwing "not found".
        FeatureWorkflow stub = testEnv.getWorkflowClient().newWorkflowStub(FeatureWorkflow.class, "feature-local-4412");
        assertThat(stub.state()).isNotNull();
    }

    @Test
    void duplicateWebhookIsIgnoredByRevAndDoesNotStartTwice() throws Exception {
        testEnv = TestWorkflowEnvironment.newInstance();
        Worker worker = testEnv.newWorker(TaskQueues.REASONING);
        worker.registerWorkflowImplementationTypes(RecordingWorkflowImpl.class);
        testEnv.start();

        InMemoryBoardAdapter board = new InMemoryBoardAdapter();
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        // First call "new" (1 row inserted), second call "duplicate" (0 rows - ON CONFLICT DO NOTHING).
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1).thenReturn(0);
        IngestedEventStore store = new IngestedEventStore(jdbcTemplate);
        Profile profile = new Profile("local",
                new BoardConfig("in-memory", "local", "PDLC", Map.of(), Map.of(), new BoardConfig.AuthConfig("none", "kv://none")),
                new RepoConfig("in-memory", "local://x", "main", "openspec"),
                new NotifyConfig("none", "none"),
                new AgentsConfig("http://stub", Map.of()),
                Map.of("G1", new GateConfig(List.of("PO", "SquadLead"), true)));
        WebhookController controller = new WebhookController(board, store, testEnv.getWorkflowClient(), profile);

        var first = controller.local(Map.of("kind", "item.created", "boardId", "4413", "rev", 1,
                "itemKind", "feature", "title", "Second feature"));
        var second = controller.local(Map.of("kind", "item.created", "boardId", "4413", "rev", 1,
                "itemKind", "feature", "title", "Second feature (replayed webhook)"));

        assertThat(first.getBody()).containsEntry("status", "ok");
        assertThat(second.getBody()).containsEntry("status", "duplicate-ignored");
    }

    @Test
    void commentAddedSignalsRunningWorkflow() throws Exception {
        testEnv = TestWorkflowEnvironment.newInstance();
        Worker worker = testEnv.newWorker(TaskQueues.REASONING);
        worker.registerWorkflowImplementationTypes(RecordingWorkflowImpl.class);
        testEnv.start();

        InMemoryBoardAdapter board = new InMemoryBoardAdapter();
        WebhookController controller = newController(testEnv.getWorkflowClient(), board);

        controller.local(Map.of("kind", "item.created", "boardId", "4414", "rev", 1,
                "itemKind", "feature", "title", "Third feature"));
        controller.local(Map.of("kind", "comment.added", "boardId", "4414", "rev", 2,
                "author", "po@acme", "text", "Current filtered view, max 10k rows."));

        long deadline = System.currentTimeMillis() + 5000;
        while (RecordingWorkflowImpl.COMMENTS.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(RecordingWorkflowImpl.COMMENTS).hasSize(1);
        assertThat(RecordingWorkflowImpl.COMMENTS.get(0).text()).contains("max 10k rows");
    }
}
