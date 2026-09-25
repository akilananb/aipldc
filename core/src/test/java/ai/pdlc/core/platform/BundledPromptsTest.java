package ai.pdlc.core.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the PDLC import: every bundled prompt is listed, loads from the classpath,
 * and - with its referenced variables declared - passes publication validation.
 */
class BundledPromptsTest {

    @Test
    void namesMatchTheFilesOnDisk() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/prompts"))) {
            Set<String> onDisk = files.map(p -> p.getFileName().toString().replace(".mustache", "")).collect(Collectors.toSet());
            assertThat(BundledPrompts.NAMES).containsExactlyInAnyOrderElementsOf(onDisk);
        }
    }

    @Test
    void everyBundledPromptIsPublishableOnceItsVariablesAreDeclared() {
        for (String name : BundledPrompts.NAMES) {
            BundledPrompts.Prompt prompt = BundledPrompts.load(name, null).orElseThrow();
            List<AgentSpec.Variable> variables = AgentSpecValidator.referencedVariables(prompt.text()).stream()
                    .map(v -> new AgentSpec.Variable(v, null, false)).toList();
            AgentSpec spec = new AgentSpec(null, "native", prompt.text(), variables,
                    new AgentSpec.ModelBinding("sonnet", List.of()), new AgentSpec.Limits(null, 600), null);

            assertThat(AgentSpecValidator.validate(name, spec, Set.of("sonnet"))).as(name).isEmpty();
            assertThat(prompt.source()).isEqualTo("bundled");
        }
    }

    @Test
    void aReadableOverrideWinsAndRolesComeFromTheNamePrefix(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("grill-questions.mustache"), "Override {{title}}");

        BundledPrompts.Prompt prompt = BundledPrompts.load("grill-questions", dir.toString()).orElseThrow();

        assertThat(prompt.text()).isEqualTo("Override {{title}}");
        assertThat(prompt.source()).startsWith("override:");
        assertThat(BundledPrompts.load("po-draft", dir.toString()).orElseThrow().source()).isEqualTo("bundled");
        assertThat(BundledPrompts.role("release-change-notes-fallback")).isEqualTo("release");
    }

    @Test
    void referencedVariablesAreTopLevelNamesOutsideSections() {
        assertThat(AgentSpecValidator.referencedVariables(
                "{{title}} {{{body}}} {{#docs}}{{url}}{{/docs}} {{^empty}}x{{/empty}} {{! note}} {{user.name}}"))
                .containsExactly("title", "body", "docs", "empty", "user");
    }
}
