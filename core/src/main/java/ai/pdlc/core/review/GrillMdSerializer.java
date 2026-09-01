package ai.pdlc.core.review;

import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.GrillQuestion;

/**
 * Renders the {@code grill.md} attachment — the {@code handoff:} YAML block literal to
 * {@code docs/agent-playbook.md} §1 "Produces". Pure formatting, no I/O.
 */
public final class GrillMdSerializer {

    private GrillMdSerializer() {
    }

    public static byte[] render(GrillHandoff grill) {
        StringBuilder sb = new StringBuilder();
        sb.append("handoff:\n");
        sb.append("  from: ").append(grill.envelope().from()).append('\n');
        sb.append("  to: ").append(grill.envelope().to()).append('\n');
        sb.append("  work_item: ").append(grill.envelope().workItem()).append('\n');
        sb.append("  state: ").append(grill.envelope().state().wireValue()).append('\n');
        sb.append("  type_decision: ").append(grill.typeDecision()).append('\n');
        sb.append("  questions:\n");
        for (GrillQuestion q : grill.questions()) {
            sb.append("    - id: ").append(q.id()).append('\n');
            sb.append("      category: ").append(q.category().wireValue()).append('\n');
            sb.append("      question: ").append(yamlScalar(q.question())).append('\n');
            sb.append("      evidence: ").append(yamlScalar(q.evidence())).append('\n');
            sb.append("      status: ").append(q.status().wireValue()).append('\n');
            if (q.answer() != null) {
                sb.append("      answer: ").append(yamlScalar(q.answer())).append('\n');
            }
            if (q.answeredBy() != null) {
                sb.append("      answered_by: ").append(q.answeredBy()).append('\n');
            }
        }
        sb.append("  parked: [").append(String.join(", ", grill.parked())).append("]\n");
        sb.append("  constraints_hit: [").append(String.join(", ", grill.constraintsHit())).append("]\n");
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String yamlScalar(String text) {
        if (text == null) {
            return "null";
        }
        return text.contains(":") || text.contains("#") ? "\"" + text.replace("\"", "\\\"") + "\"" : text;
    }
}
