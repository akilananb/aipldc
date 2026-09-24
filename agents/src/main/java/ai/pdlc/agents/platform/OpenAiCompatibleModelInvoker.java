package ai.pdlc.agents.platform;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * {@link ModelInvoker} over Spring AI's {@link OpenAiChatModel} - the same client Embabel uses for
 * the PDLC agents - built per call from the catalog's connection, so a model or connection added,
 * rotated or revoked in the catalog applies to the next run without restarting agents. Client-side
 * retries are off: retrying is the workflow's decision.
 */
@Component
public class OpenAiCompatibleModelInvoker implements ModelInvoker {

    @Override
    public Reply call(Endpoint endpoint, String prompt, Integer maxOutputTokens, Duration timeout) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .baseUrl(endpoint.baseUrl())
                .apiKey(endpoint.apiKey())
                .model(endpoint.providerModel())
                .timeout(timeout)
                .maxRetries(0);
        if (maxOutputTokens != null) {
            options.maxTokens(maxOutputTokens);
        }
        OpenAiChatOptions built = options.build();
        ChatResponse response = OpenAiChatModel.builder().options(built).build().call(new Prompt(prompt, built));
        String text = response.getResult() == null ? null : response.getResult().getOutput().getText();
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        return new Reply(text, positiveOrNull(usage == null ? null : usage.getPromptTokens()),
                positiveOrNull(usage == null ? null : usage.getCompletionTokens()));
    }

    /** Spring AI reports 0 for a provider that sent no usage block; that is "unknown", not zero. */
    private static Integer positiveOrNull(Integer tokens) {
        return tokens == null || tokens <= 0 ? null : tokens;
    }
}
