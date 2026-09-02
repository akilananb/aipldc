package ai.pdlc.agents.mention;

import ai.pdlc.agents.activities.AgentContext;
import ai.pdlc.agents.templates.PromptTemplates;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.domain.AgentMentionRequest;
import ai.pdlc.core.port.RepoPort;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.common.ai.model.LlmOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handles every @-mentioned reviewer agent (analyst/architect/qa/dev) — one class per pilot agent
 * rather than near-identical classes, since the only difference between them is which prompt
 * template is rendered (and, for {@code dev}, which extra context is gathered). One real LLM call
 * per mention; the raw response IS the markdown draft stored on the comment (no JSON parsing).
 */
@Component
public class MentionAgent {

    private static final Logger log = LoggerFactory.getLogger(MentionAgent.class);

    private static final int MAX_CODE_FILES = 10;
    private static final int MAX_FILE_CHARS = 8000;

    /** Mirrors control-plane's {@code AgentMentions.AGENTS} (agents module cannot depend on
     * control-plane); the two sets must be kept in sync when a new mention agent is added. */
    private static final Set<String> AGENTS = Set.of("analyst", "architect", "qa", "dev");

    private final Ai ai;
    private final PromptTemplates templates;
    private final String mentionModel;
    private final RepoPort repo;
    private final Profile activeProfile;

    public MentionAgent(Ai ai, PromptTemplates templates, Profile activeProfile, RepoPort repo) {
        this.ai = ai;
        this.templates = templates;
        this.activeProfile = activeProfile;
        this.repo = repo;
        var role = activeProfile.agents().roles().get("mention");
        this.mentionModel = role != null ? role.model() : null;
    }

    public String analyze(AgentMentionRequest request) {
        if (!AGENTS.contains(request.agentName())) {
            throw new IllegalArgumentException("Unknown mention agent: " + request.agentName());
        }
        String defaultBranch = activeProfile.repo().defaultBranch();
        String storyMarkdown = AgentContext.readFile(repo, defaultBranch, request.specChangePath() + "/proposal.md");
        String areasConfig = AgentContext.readFile(repo, defaultBranch, "openspec/config.yaml");

        Map<String, Object> view = new HashMap<>();
        view.put("question", request.question());
        view.put("target", request.target());
        view.put("storyMarkdown", storyMarkdown == null ? "" : storyMarkdown);
        view.put("areasConfig", areasConfig == null ? "" : areasConfig);

        if ("dev".equals(request.agentName())) {
            String tasksMd = AgentContext.readFile(repo, defaultBranch, request.specChangePath() + "/tasks.md");
            String buildBranch = "story/" + request.boardId();
            view.put("tasksMd", tasksMd == null ? "" : tasksMd);
            view.put("buildBranch", buildBranch);
            view.put("codeFiles", loadCodeFiles(areasConfig, buildBranch, defaultBranch));
        }

        String prompt = templates.render("mention-" + request.agentName(), view);
        return promptRunner().generateText(prompt);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> loadCodeFiles(String areasConfig, String buildBranch, String defaultBranch) {
        Set<String> paths = new LinkedHashSet<>();
        if (areasConfig != null) {
            try {
                Map<String, Object> root = (Map<String, Object>) new Yaml().load(areasConfig);
                Map<String, Object> areas = root == null ? null : (Map<String, Object>) root.get("areas");
                if (areas != null) {
                    for (Object areaNode : areas.values()) {
                        Map<String, Object> area = (Map<String, Object>) areaNode;
                        for (Object touch : (List<?>) area.getOrDefault("touches", List.of())) {
                            paths.add(String.valueOf(touch));
                        }
                        Object test = area.get("test");
                        if (test != null) {
                            paths.add(String.valueOf(test));
                        }
                    }
                }
            } catch (RuntimeException e) {
                log.warn("[mention:dev] openspec/config.yaml present but unparseable: {}", e.toString());
            }
        }

        List<Map<String, String>> codeFiles = new ArrayList<>();
        for (String path : paths) {
            if (codeFiles.size() >= MAX_CODE_FILES) {
                break;
            }
            String ref = buildBranch;
            String content = AgentContext.readFile(repo, buildBranch, path);
            if (content == null) {
                ref = defaultBranch;
                content = AgentContext.readFile(repo, defaultBranch, path);
            }
            if (content == null) {
                continue;
            }
            if (content.length() > MAX_FILE_CHARS) {
                content = content.substring(0, MAX_FILE_CHARS) + "\n… (truncated)";
            }
            codeFiles.add(Map.of("path", path, "ref", ref, "content", content));
        }
        return codeFiles;
    }

    private PromptRunner promptRunner() {
        if (mentionModel == null || mentionModel.isBlank()) {
            return ai.withDefaultLlm();
        }
        return ai.withLlm(LlmOptions.withModel(mentionModel));
    }
}
