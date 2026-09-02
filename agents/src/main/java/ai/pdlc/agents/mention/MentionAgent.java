package ai.pdlc.agents.mention;

import ai.pdlc.agents.activities.AgentContext;
import ai.pdlc.agents.templates.PromptTemplates;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.domain.AgentMentionRequest;
import ai.pdlc.core.port.RepoPort;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.common.ai.model.LlmOptions;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Handles every @-mentioned reviewer agent (analyst/architect/qa) — one class per pilot agent
 * rather than three near-identical classes, since the only difference between them is which
 * prompt template is rendered. One real LLM call per mention; the raw response IS the markdown
 * draft stored on the comment (no JSON parsing).
 */
@Component
public class MentionAgent {

    /** Mirrors control-plane's {@code AgentMentions.AGENTS} (agents module cannot depend on
     * control-plane); the two sets must be kept in sync when a new mention agent is added. */
    private static final Set<String> AGENTS = Set.of("analyst", "architect", "qa");

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

        String prompt = templates.render("mention-" + request.agentName(), Map.of(
                "question", request.question(),
                "target", request.target(),
                "storyMarkdown", storyMarkdown == null ? "" : storyMarkdown,
                "areasConfig", areasConfig == null ? "" : areasConfig));
        return promptRunner().generateText(prompt);
    }

    private PromptRunner promptRunner() {
        if (mentionModel == null || mentionModel.isBlank()) {
            return ai.withDefaultLlm();
        }
        return ai.withLlm(LlmOptions.withModel(mentionModel));
    }
}
