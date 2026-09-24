package ai.pdlc.agents.platform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link ModelInvoker} over Spring AI's {@link OpenAiChatModel} - the same client Embabel uses for
 * the PDLC agents - built per call from the catalog's connection, so a model or connection added,
 * rotated or revoked in the catalog applies to the next run without restarting agents. Client-side
 * retries are off: retrying is the workflow's decision.
 *
 * <p>Tools are offered as definitions only. {@code OpenAiChatModel.call} returns the requested
 * calls without executing them; the callbacks registered here refuse to run anyway, so a change in
 * Spring AI's defaults could never execute a tool outside the runner's {@code ToolExecutor}.
 */
@Component
public class OpenAiCompatibleModelInvoker implements ModelInvoker {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public Reply chat(Endpoint endpoint, List<Message> history, List<ToolDef> tools, Integer maxOutputTokens,
                      Duration timeout) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .baseUrl(endpoint.baseUrl())
                .apiKey(endpoint.apiKey())
                .model(endpoint.providerModel())
                .timeout(timeout)
                .maxRetries(0);
        if (maxOutputTokens != null) {
            options.maxTokens(maxOutputTokens);
        }
        if (!tools.isEmpty()) {
            options.toolCallbacks(tools.stream().map(OpenAiCompatibleModelInvoker::definitionOnly).toList());
        }
        OpenAiChatOptions built = options.build();
        ChatResponse response = OpenAiChatModel.builder().options(built).build().call(new Prompt(toSpring(history), built));
        var output = response.getResult() == null ? null : response.getResult().getOutput();
        List<ToolCall> calls = output == null || output.getToolCalls() == null ? List.of()
                : output.getToolCalls().stream().map(c -> new ToolCall(c.id(), c.name(), c.arguments())).toList();
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        return new Reply(output == null ? null : output.getText(),
                positiveOrNull(usage == null ? null : usage.getPromptTokens()),
                positiveOrNull(usage == null ? null : usage.getCompletionTokens()), calls);
    }

    private static List<org.springframework.ai.chat.messages.Message> toSpring(List<Message> history) {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        for (Message m : history) {
            switch (m) {
                case UserMessage u -> messages.add(new org.springframework.ai.chat.messages.UserMessage(u.text()));
                case AssistantMessage a -> messages.add(org.springframework.ai.chat.messages.AssistantMessage.builder()
                        .content(a.text() == null ? "" : a.text())
                        .toolCalls(a.toolCalls().stream()
                                .map(c -> new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                        c.id(), "function", c.name(), c.argumentsJson()))
                                .toList())
                        .build());
                case ToolResults r -> messages.add(ToolResponseMessage.builder()
                        .responses(r.results().stream()
                                .map(t -> new ToolResponseMessage.ToolResponse(t.callId(), t.name(), t.content()))
                                .toList())
                        .build());
            }
        }
        return messages;
    }

    private static ToolCallback definitionOnly(ToolDef tool) {
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(schemaJson(tool.inputSchema()))
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                throw new IllegalStateException("Tools run only through the runner's ToolExecutor");
            }
        };
    }

    private static String schemaJson(Map<String, Object> schema) {
        try {
            return JSON.writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Tool input schema is not serializable", e);
        }
    }

    /** Spring AI reports 0 for a provider that sent no usage block; that is "unknown", not zero. */
    private static Integer positiveOrNull(Integer tokens) {
        return tokens == null || tokens <= 0 ? null : tokens;
    }
}
