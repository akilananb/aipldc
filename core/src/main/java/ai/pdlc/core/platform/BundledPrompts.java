package ai.pdlc.core.platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The PDLC prompt templates bundled on the classpath at {@code /prompts/<name>.mustache}
 * ({@code core/src/main/resources/prompts/}). The agents' {@code PromptTemplates} renders them for
 * the legacy pipeline; control-plane's {@code PdlcImportSeeder} imports them as published platform
 * agents. Lookup order matches {@code PromptTemplates}: a readable {@code prompts_dir} override
 * first, then the bundled default.
 */
public final class BundledPrompts {

    /** Every bundled prompt; keep in step with {@code core/src/main/resources/prompts/} (a test enforces it). */
    public static final List<String> NAMES = List.of(
            "grill-questions",
            "mention-analyst",
            "mention-architect",
            "mention-dev",
            "mention-qa",
            "plan-next-step",
            "po-draft",
            "po-revise",
            "quality-eval",
            "release-change-notes",
            "release-change-notes-fallback",
            "release-monitor-rules",
            "release-rollout-plan",
            "release-test-evidence",
            "review-summary");

    /** A prompt's text and where it came from ({@code bundled} or {@code override:<path>}). */
    public record Prompt(String name, String text, String source) {
    }

    private BundledPrompts() {
    }

    public static Optional<Prompt> load(String name, String promptsDir) {
        if (promptsDir != null && !promptsDir.isBlank()) {
            Path override = Path.of(promptsDir, name + ".mustache");
            if (Files.isReadable(override)) {
                try {
                    return Optional.of(new Prompt(name, Files.readString(override, StandardCharsets.UTF_8), "override:" + override));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        try (InputStream in = BundledPrompts.class.getResourceAsStream("/prompts/" + name + ".mustache")) {
            return in == null ? Optional.empty()
                    : Optional.of(new Prompt(name, new String(in.readAllBytes(), StandardCharsets.UTF_8), "bundled"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The role a prompt belongs to, by its name prefix ({@code grill-questions} → {@code grill}). */
    public static String role(String name) {
        int dash = name.indexOf('-');
        return dash < 0 ? name : name.substring(0, dash);
    }
}
