package ai.pdlc.agents.platform;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * One chat-completion call against an OpenAI-compatible endpoint chosen at run time from the
 * model catalog (docs/phase-1-execution-spec.md slice 3/4) - not from startup config.
 *
 * <p>Tool calling (docs/phase-2-execution-spec.md slice 2.1): {@link #chat} offers tool
 * definitions and returns the model's requested calls <em>unexecuted</em>. The runner owns the
 * loop and sends every call through {@code ToolExecutor}; an invoker never executes a tool.
 */
public interface ModelInvoker {

    /** Where and how to call; {@code apiKey} is resolved just before the call and never stored. */
    record Endpoint(String baseUrl, String apiKey, String providerModel) {
        @Override
        public String toString() {
            return "Endpoint[baseUrl=" + baseUrl + ", providerModel=" + providerModel + "]";
        }
    }

    /** A function the model may request: the pinned tool's id, description and input schema. */
    record ToolDef(String name, String description, Map<String, Object> inputSchema) {
    }

    /** A call the model requested; {@code argumentsJson} is untrusted model output. */
    record ToolCall(String id, String name, String argumentsJson) {
    }

    /** One turn of the conversation sent to the model. */
    sealed interface Message permits UserMessage, AssistantMessage, ToolResults {
    }

    record UserMessage(String text) implements Message {
    }

    record AssistantMessage(String text, List<ToolCall> toolCalls) implements Message {
    }

    /** The executor's answers to one assistant turn's calls, in call order. */
    record ToolResults(List<ToolResult> results) implements Message {
    }

    record ToolResult(String callId, String name, String content) {
    }

    /**
     * Token counts are {@code null} when the provider did not report them - unknown, never guessed.
     * {@code toolCalls} is empty when the model answered in text.
     */
    record Reply(String text, Integer promptTokens, Integer completionTokens, List<ToolCall> toolCalls) {
        public Reply(String text, Integer promptTokens, Integer completionTokens) {
            this(text, promptTokens, completionTokens, List.of());
        }

        public Reply {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }

    Reply chat(Endpoint endpoint, List<Message> history, List<ToolDef> tools, Integer maxOutputTokens, Duration timeout);

    /** A single prompt, no tools. */
    default Reply call(Endpoint endpoint, String prompt, Integer maxOutputTokens, Duration timeout) {
        return chat(endpoint, List.of(new UserMessage(prompt)), List.of(), maxOutputTokens, timeout);
    }
}
