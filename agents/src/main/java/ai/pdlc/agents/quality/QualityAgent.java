package ai.pdlc.agents.quality;

import ai.pdlc.agents.templates.PromptTemplates;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.domain.QualityReport;
import com.embabel.agent.api.common.Ai;
import com.embabel.common.ai.model.LlmOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quality agent — evaluates one story/task draft's INVEST/clarity/testability quality. A story
 * verdict hard-blocks gate 1 ({@link ai.pdlc.core.workflow.FeatureWorkflowImpl}); a task verdict is
 * advisory only (the plan agent is deterministic, no revise path). One real Embabel {@link Ai}
 * call, marked {@code [agent:quality]}; the verdict/score/findings parse is deterministic Java —
 * see {@link #parse}.
 */
@Component
public class QualityAgent {

    private static final Pattern VERDICT = Pattern.compile("(?im)^VERDICT:\\s*(PASS|FAIL)\\s*$");
    private static final Pattern SCORE = Pattern.compile("(?im)^SCORE:\\s*(\\d+)\\s*$");
    private static final Pattern FINDINGS_SECTION = Pattern.compile("(?ims)^##\\s*Findings\\s*$(.*?)(?=^##|\\z)");
    private static final Pattern FINDING_LINE = Pattern.compile("^\\s*-\\s*(.+?)\\s*$");

    private final Ai ai;
    private final PromptTemplates templates;
    private final String qualityModel;

    public QualityAgent(Ai ai, PromptTemplates templates, Profile activeProfile) {
        this.ai = ai;
        this.templates = templates;
        var role = activeProfile.agents().roles().get("quality");
        this.qualityModel = role != null ? role.model() : null;
    }

    public QualityReport evaluate(String subjectKind, String contentMd) {
        String prompt = templates.render("quality-eval", Map.of("subjectKind", subjectKind, "content", contentMd));
        return parse(promptRunner().generateText(prompt), subjectKind);
    }

    /** No {@code VERDICT} line means the agent returned unparseable output — fail-safe blocks
     * gate 1 instead of silently passing an ungraded story. */
    static QualityReport parse(String out, String subjectKind) {
        Matcher verdictMatcher = VERDICT.matcher(out);
        if (!verdictMatcher.find()) {
            return new QualityReport(subjectKind, false, 0, List.of("quality agent returned unparseable output"), out);
        }
        boolean passed = "PASS".equalsIgnoreCase(verdictMatcher.group(1));

        int score = 0;
        Matcher scoreMatcher = SCORE.matcher(out);
        if (scoreMatcher.find()) {
            score = Integer.parseInt(scoreMatcher.group(1));
        }

        List<String> findings = new ArrayList<>();
        Matcher findingsMatcher = FINDINGS_SECTION.matcher(out);
        if (findingsMatcher.find()) {
            for (String line : findingsMatcher.group(1).lines().toList()) {
                Matcher lineMatcher = FINDING_LINE.matcher(line);
                if (lineMatcher.matches()) {
                    findings.add(lineMatcher.group(1).trim());
                }
            }
        }

        return new QualityReport(subjectKind, passed, score, findings, out);
    }

    private com.embabel.agent.api.common.PromptRunner promptRunner() {
        if (qualityModel == null || qualityModel.isBlank()) {
            return ai.withDefaultLlm();
        }
        return ai.withLlm(LlmOptions.withModel(qualityModel));
    }
}
