package ai.pdlc.agents.platform;

import java.time.Duration;

/**
 * One chat-completion call against an OpenAI-compatible endpoint chosen at run time from the
 * model catalog (docs/phase-1-execution-spec.md slice 3/4) - not from startup config.
 */
public interface ModelInvoker {

    /** Where and how to call; {@code apiKey} is resolved just before the call and never stored. */
    record Endpoint(String baseUrl, String apiKey, String providerModel) {
        @Override
        public String toString() {
            return "Endpoint[baseUrl=" + baseUrl + ", providerModel=" + providerModel + "]";
        }
    }

    /** Token counts are {@code null} when the provider did not report them - unknown, never guessed. */
    record Reply(String text, Integer promptTokens, Integer completionTokens) {
    }

    Reply call(Endpoint endpoint, String prompt, Integer maxOutputTokens, Duration timeout);
}
