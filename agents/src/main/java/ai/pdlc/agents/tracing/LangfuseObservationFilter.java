package ai.pdlc.agents.tracing;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationView;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.content.Content;
import org.springframework.ai.observation.ObservabilityHelper;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;

/** Copies the in-flight {@link AgentRunContext} onto every observation and, for Spring AI chat
 * observations, exposes prompt/completion as {@code gen_ai.prompt}/{@code gen_ai.completion}
 * (the attributes Langfuse maps to observation input/output). */
public final class LangfuseObservationFilter implements ObservationFilter {

    @Override
    public Observation.Context map(Observation.Context context) {
        AgentRunContext run = resolveRunContext(context);
        if (run != null) {
            run.stamp(context);
        }
        if (context instanceof ChatModelObservationContext chat) {
            chat.addHighCardinalityKeyValue(KeyValue.of("gen_ai.prompt", ObservabilityHelper.concatenateStrings(prompts(chat))));
            chat.addHighCardinalityKeyValue(KeyValue.of("gen_ai.completion", ObservabilityHelper.concatenateStrings(completions(chat))));
        }
        return context;
    }

    /** {@link AgentRunTracer} stores the run's {@link AgentRunContext} directly on the root
     * observation's context (not a thread-local: this filter runs at {@code observation.stop()},
     * which for a nested Spring AI chat observation can happen on a different thread than the one
     * that created it). Walk the parent-observation chain — captured at creation time on the
     * original thread, so it survives the stop-time thread hop — until we find it. */
    static AgentRunContext resolveRunContext(Observation.ContextView context) {
        Observation.ContextView current = context;
        while (current != null) {
            AgentRunContext run = current.get(AgentRunContext.class);
            if (run != null) {
                return run;
            }
            ObservationView parent = current.getParentObservation();
            current = parent == null ? null : parent.getContextView();
        }
        return null;
    }

    private static List<String> prompts(ChatModelObservationContext chat) {
        if (chat.getRequest() == null || CollectionUtils.isEmpty(chat.getRequest().getInstructions())) {
            return List.of();
        }
        return chat.getRequest().getInstructions().stream().map(Content::getText).toList();
    }

    private static List<String> completions(ChatModelObservationContext chat) {
        if (chat.getResponse() == null || CollectionUtils.isEmpty(chat.getResponse().getResults())) {
            return List.of();
        }
        return chat.getResponse().getResults().stream()
                .map(Generation::getOutput)
                .filter(out -> out != null && StringUtils.hasText(out.getText()))
                .map(out -> out.getText())
                .toList();
    }
}
