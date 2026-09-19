package ai.pdlc.controlplane.temporal;

import ai.pdlc.controlplane.web.dto.PlanResultRequest;
import ai.pdlc.controlplane.web.dto.PlanResultRequest.PlannedTask;
import ai.pdlc.core.config.AgentsConfig;
import ai.pdlc.core.config.BoardConfig;
import ai.pdlc.core.config.NotifyConfig;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.RepoConfig;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Handoff;
import ai.pdlc.core.domain.PlanResult;
import ai.pdlc.core.domain.PoHandoff;
import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.VerifierResult;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.workflow.BuildActivities;
import ai.pdlc.core.workflow.BuildResult;
import ai.pdlc.core.workflow.TaskQueues;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.ActivityCompletionClient;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the new-behavior contract at the heart of this change: {@link BuildActivitiesImpl#runTask}
 * parks the Temporal activity ({@code doNotCompleteOnReturn}) instead of running the build loop
 * in-process, and only the standalone build agent posting a result back through {@link
 * BuildTaskService#complete} (here invoked directly against the captured task token, mirroring
 * what the REST layer does) lets the workflow resume.
 */
class BuildTaskAsyncCompletionTest {

    @WorkflowInterface
    public interface TestBuildWorkflow {
        @WorkflowMethod
        BuildResult run(WorkItemRef story, Task task, String branch, String baseBranch);
    }

    public static class TestBuildWorkflowImpl implements TestBuildWorkflow {
        private static final ActivityOptions BUILD_ACTIVITY_OPTIONS = ActivityOptions.newBuilder()
                .setTaskQueue(TaskQueues.BUILD)
                .setStartToCloseTimeout(Duration.ofMinutes(30))
                .setHeartbeatTimeout(Duration.ofMinutes(2))
                .build();

        private final BuildActivities build = Workflow.newActivityStub(BuildActivities.class, BUILD_ACTIVITY_OPTIONS);

        @Override
        public BuildResult run(WorkItemRef story, Task task, String branch, String baseBranch) {
            return build.runTask(story, task, branch, baseBranch, List.of());
        }
    }

    private TestWorkflowEnvironment testEnv;

    @AfterEach
    void tearDown() {
        if (testEnv != null) {
            testEnv.close();
        }
    }

    @Test
    void parkedActivityCompletesAsynchronouslyWithThePostedResult() throws Exception {
        testEnv = TestWorkflowEnvironment.newInstance();

        Worker reasoningWorker = testEnv.newWorker(TaskQueues.REASONING);
        reasoningWorker.registerWorkflowImplementationTypes(TestBuildWorkflowImpl.class);

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ArgumentCaptor<Object[]> updateArgs = ArgumentCaptor.forClass(Object[].class);
        when(jdbcTemplate.update(anyString(), updateArgs.capture())).thenReturn(1);

        ActivityCompletionClient completionClient = testEnv.getWorkflowClient().newActivityCompletionClient();
        BuildTaskService service = new BuildTaskService(jdbcTemplate, completionClient);
        Profile activeProfile = new Profile("local",
                new BoardConfig("in-memory", "local", "PDLC", Map.of(), Map.of(), new BoardConfig.AuthConfig("none", "kv://none")),
                new RepoConfig("git", "https://example.test/orders-service.git", "main", "openspec"),
                new NotifyConfig("none", "none"),
                new AgentsConfig("http://stub", null, Map.of()),
                Map.of());
        BuildActivitiesImpl buildActivities = new BuildActivitiesImpl(service, activeProfile);

        Worker buildWorker = testEnv.newWorker(TaskQueues.BUILD);
        buildWorker.registerActivitiesImplementations(buildActivities);

        testEnv.start();

        WorkflowClient client = testEnv.getWorkflowClient();
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.REASONING)
                .setWorkflowId("test-build-" + UUID.randomUUID())
                .build();
        TestBuildWorkflow stub = client.newWorkflowStub(TestBuildWorkflow.class, options);

        WorkItemRef story = new WorkItemRef("local", "4414");
        Task task = new Task("T1", "Add CSV export", "brief", "orders", "export-csv", List.of("src/export.js"),
                "test/export.test.js", new Task.TaskBudget(5, 20_000L, Duration.ofMinutes(10)), List.of());
        WorkflowClient.start(stub::run, story, task, "story/4414", "main");

        WorkflowStub untyped = WorkflowStub.fromTyped(stub);

        // (1) workflow does not complete while the row is parked.
        assertThatThrownBy(() -> untyped.getResult(2, TimeUnit.SECONDS, BuildResult.class))
                .isInstanceOf(TimeoutException.class);

        // Grab the base64 task token from the enqueue INSERT's captured args (payload_json is
        // arg index 4, task_token is arg index 5 - see BuildTaskService#enqueue).
        Object[] insertArgs = updateArgs.getAllValues().stream()
                .filter(args -> args.length == 6)
                .findFirst()
                .orElseThrow(() -> new AssertionError("enqueue INSERT was never captured"));
        String payloadJson = (String) insertArgs[4];
        String base64Token = (String) insertArgs[5];
        byte[] token = Base64.getDecoder().decode(base64Token);

        // (3) payload JSON carries the ISO-8601 duration and the repo object.
        assertThat(payloadJson).contains("\"maxWallClock\":\"PT10M\"");
        assertThat(payloadJson).contains("\"repo\"");
        assertThat(payloadJson).contains("orders-service.git");

        // (2) completing via the captured token resumes the workflow with that exact result.
        VerifierResult verifier = new VerifierResult("green", Map.of("export-csv", true), true, "all tests pass");
        BuildResult posted = new BuildResult("T1", "deadbeef", verifier, 3, 15_000L, List.of("src/export.js"),
                "implemented export-csv", null);
        completionClient.complete(token, posted);

        BuildResult actual = untyped.getResult(5, TimeUnit.SECONDS, BuildResult.class);
        assertThat(actual).isEqualTo(posted);
    }

    @WorkflowInterface
    public interface TestPlanWorkflow {
        @WorkflowMethod
        PlanResult run(WorkItemRef story, PoHandoff po);
    }

    public static class TestPlanWorkflowImpl implements TestPlanWorkflow {
        private static final ActivityOptions PLAN_ACTIVITY_OPTIONS = ActivityOptions.newBuilder()
                .setTaskQueue(TaskQueues.BUILD)
                .setStartToCloseTimeout(Duration.ofMinutes(30))
                .setHeartbeatTimeout(Duration.ofMinutes(2))
                .build();

        private final BuildActivities build = Workflow.newActivityStub(BuildActivities.class, PLAN_ACTIVITY_OPTIONS);

        @Override
        public PlanResult run(WorkItemRef story, PoHandoff po) {
            return build.planTasks(story, po);
        }
    }

    @Test
    void parkedPlanActivityCompletesWithThePostedPlan() throws Exception {
        testEnv = TestWorkflowEnvironment.newInstance();

        Worker reasoningWorker = testEnv.newWorker(TaskQueues.REASONING);
        reasoningWorker.registerWorkflowImplementationTypes(TestPlanWorkflowImpl.class);

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ArgumentCaptor<Object[]> updateArgs = ArgumentCaptor.forClass(Object[].class);
        when(jdbcTemplate.update(anyString(), updateArgs.capture())).thenReturn(1);

        ActivityCompletionClient completionClient = testEnv.getWorkflowClient().newActivityCompletionClient();
        BuildTaskService service = new BuildTaskService(jdbcTemplate, completionClient);
        Profile activeProfile = new Profile("local",
                new BoardConfig("in-memory", "local", "PDLC", Map.of(), Map.of(), new BoardConfig.AuthConfig("none", "kv://none")),
                new RepoConfig("git", "https://example.test/orders-service.git", "main", "openspec"),
                new NotifyConfig("none", "none"),
                new AgentsConfig("http://stub", null, Map.of()),
                Map.of());
        BuildActivitiesImpl buildActivities = new BuildActivitiesImpl(service, activeProfile);

        Worker buildWorker = testEnv.newWorker(TaskQueues.BUILD);
        buildWorker.registerActivitiesImplementations(buildActivities);

        testEnv.start();

        WorkflowClient client = testEnv.getWorkflowClient();
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.REASONING)
                .setWorkflowId("test-plan-" + UUID.randomUUID())
                .build();
        TestPlanWorkflow stub = client.newWorkflowStub(TestPlanWorkflow.class, options);

        WorkItemRef story = new WorkItemRef("local", "4415");
        PoHandoff po = new PoHandoff(
                new Handoff("po-agent", "gate-1", "4415", CanonicalState.READY_FOR_STORY, List.of(), 0.9, List.of(), List.of()),
                "4400", "openspec/changes/export-orders-csv", List.of("a", "b"), Map.of(), List.of("orders"),
                Map.of(), List.of(), Map.of());
        WorkflowClient.start(stub::run, story, po);

        WorkflowStub untyped = WorkflowStub.fromTyped(stub);

        // workflow does not complete while the plan row is parked.
        assertThatThrownBy(() -> untyped.getResult(2, TimeUnit.SECONDS, PlanResult.class))
                .isInstanceOf(TimeoutException.class);

        // Grab the plan enqueue INSERT's payload_json/task_token (task_id 'plan' is arg index 2).
        Object[] insertArgs = updateArgs.getAllValues().stream()
                .filter(args -> args.length == 6 && "plan".equals(args[2]))
                .findFirst()
                .orElseThrow(() -> new AssertionError("enqueuePlan INSERT was never captured"));
        String payloadJson = (String) insertArgs[4];
        String base64Token = (String) insertArgs[5];

        // completePlan re-reads the row (state/task_token) and the stored payload (for po.scenarios).
        UUID rowId = UUID.randomUUID();
        ResultSet fakeRs = mock(ResultSet.class);
        when(fakeRs.getString("id")).thenReturn(rowId.toString());
        when(fakeRs.getString("state")).thenReturn("claimed");
        when(fakeRs.getString("task_token")).thenReturn(base64Token);
        when(fakeRs.getString("claimed_by")).thenReturn("test-agent");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any()))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(fakeRs, 0));
                });
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any())).thenReturn(payloadJson);

        PlannedTask t1 = new PlannedTask("T1", "Rate limit on /export", "brief", "orders", "a",
                List.of("src/export.js"), "test/export.test.js", List.of());
        PlannedTask t2 = new PlannedTask("T2", "Second export guard", "brief2", "orders", "b",
                List.of("src/export.js"), "test/export.test.js", List.of());

        PlanResult result = service.completePlan(rowId, new PlanResultRequest(List.of(t1, t2), List.of()));

        assertThat(result.plan().waves()).containsExactly(List.of("T1"), List.of("T2"));
        assertThat(result.checksByTaskId().get("T2").reportMd())
                .contains("blocked by: none")
                .contains("src/export.js (exists)");

        PlanResult actual = untyped.getResult(5, TimeUnit.SECONDS, PlanResult.class);
        assertThat(actual).isEqualTo(result);

        assertThatThrownBy(() -> service.completePlan(rowId, new PlanResultRequest(List.of(t1), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coverage: scenario");
    }
}
