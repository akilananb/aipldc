package ai.pdlc.agents.plan;

import ai.pdlc.agents.activities.AgentContext;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Handoff;
import ai.pdlc.core.domain.PlanHandoff;
import ai.pdlc.core.domain.PoHandoff;
import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.RepoPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plan agent (build-order phase 3) as a plain Spring service — deterministic, no LLM call, matching
 * the pre-decided guard-method fallback: one task per scenario in the approved story's spec delta,
 * sequenced into waves by the {@code touches} conflict/dependency order playbook §3 "Validates"
 * calls out (coverage, DAG, no in-wave file conflicts — all three hold by construction here, see
 * {@link PlanValidator} for the standalone checks used in tests).
 *
 * <p>{@code touches}/{@code test} per area come from {@code openspec/config.yaml}'s path map
 * ({@link PoHandoff#areas()}'s own doc comment names this as the source); a hard-coded default is
 * used only when that file is missing or doesn't cover the story's area.
 */
@Component
public class PlanAgent {

    private static final Logger log = LoggerFactory.getLogger(PlanAgent.class);

    private final RepoPort repo;
    private final Profile activeProfile;

    public PlanAgent(RepoPort repo, Profile activeProfile) {
        this.repo = repo;
        this.activeProfile = activeProfile;
    }

    public PlanHandoff plan(WorkItemRef story, PoHandoff po) {
        String area = po.areas().isEmpty() ? "default" : po.areas().get(0);
        AreaConfig areaConfig = resolveAreaConfig(area);

        List<Task> tasks = new ArrayList<>();
        List<List<String>> waves = new ArrayList<>();
        List<Set<String>> waveTouchedFiles = new ArrayList<>();

        int n = 1;
        for (String scenario : po.scenarios()) {
            String id = "T" + n++;
            List<String> touches = areaConfig.touches();
            Task.TaskBudget budget = new Task.TaskBudget(6, 120_000L, Duration.ofMinutes(10));

            int waveIndex = -1;
            for (int i = 0; i < waveTouchedFiles.size(); i++) {
                if (touches.stream().noneMatch(waveTouchedFiles.get(i)::contains)) {
                    waveIndex = i;
                    break;
                }
            }
            List<String> blockedBy = new ArrayList<>();
            int conflictScanLimit = waveIndex == -1 ? waves.size() : waveIndex;
            for (int i = 0; i < conflictScanLimit; i++) {
                for (String earlierId : waves.get(i)) {
                    Task earlier = taskById(tasks, earlierId);
                    if (touches.stream().anyMatch(earlier.touches()::contains)) {
                        blockedBy.add(earlierId);
                    }
                }
            }
            if (waveIndex == -1) {
                waveIndex = waves.size();
                waves.add(new ArrayList<>());
                waveTouchedFiles.add(new LinkedHashSet<>());
            }
            waves.get(waveIndex).add(id);
            waveTouchedFiles.get(waveIndex).addAll(touches);

            tasks.add(new Task(id, "Implement \"" + scenario + "\"", area, scenario, touches,
                    areaConfig.test(), budget, blockedBy));
        }

        Handoff envelope = new Handoff("plan-agent", "build-worker", story.boardId(), CanonicalState.PLANNED,
                List.of("repo:" + area + "@" + activeProfile.repo().defaultBranch(), "po:" + po.change()),
                0.85, List.of(), List.of());
        return new PlanHandoff(envelope, tasks, waves);
    }

    private static Task taskById(List<Task> tasks, String id) {
        return tasks.stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No such task: " + id));
    }

    record AreaConfig(List<String> touches, String test) {
    }

    @SuppressWarnings("unchecked")
    private AreaConfig resolveAreaConfig(String area) {
        String yaml = AgentContext.readFile(repo, activeProfile.repo().defaultBranch(), "openspec/config.yaml");
        if (yaml != null) {
            try {
                Map<String, Object> root = (Map<String, Object>) new Yaml().load(yaml);
                Map<String, Object> areas = (Map<String, Object>) root.get("areas");
                Map<String, Object> areaNode = areas == null ? null : (Map<String, Object>) areas.get(area);
                if (areaNode != null) {
                    List<String> touches = ((List<?>) areaNode.getOrDefault("touches", List.of())).stream()
                            .map(String::valueOf).toList();
                    Object test = areaNode.get("test");
                    if (!touches.isEmpty() && test != null) {
                        return new AreaConfig(touches, String.valueOf(test));
                    }
                }
            } catch (RuntimeException e) {
                log.warn("[plan] openspec/config.yaml present but unparseable for area {}: {}", area, e.toString());
            }
        }
        log.warn("[plan] no openspec/config.yaml area entry for '{}'; falling back to a naming-convention default", area);
        return new AreaConfig(List.of("src/" + area + ".js"), "test/" + area + ".test.js");
    }
}
