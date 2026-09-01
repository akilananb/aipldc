package ai.pdlc.agents.templates;

import ai.pdlc.core.config.PdlcConfigException;
import ai.pdlc.core.config.Profile;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Renders the LLM-prompt and release-pack-document Mustache templates (plan "Externalize hardcoded
 * prompts & release-doc templates"). Resolution per {@link #render}: a team's {@code prompts_dir}
 * override (one {@code <name>.mustache} file at a time) takes precedence over the bundled classpath
 * default; neither present is a startup-shaped config error, not a template-authoring bug, so it
 * throws the existing {@link PdlcConfigException}.
 *
 * <p>No caching: a handful of renders per story, and teams editing override files expect the change
 * to take effect without restarting the agents process.
 */
@Component
public class PromptTemplates {

    private final String promptsDir;

    @Autowired
    public PromptTemplates(Profile activeProfile) {
        this(activeProfile.agents().promptsDir());
    }

    /** Visible for testing: bypasses the {@link Profile} bean to point directly at an override dir. */
    PromptTemplates(String promptsDir) {
        this.promptsDir = promptsDir;
    }

    public String render(String name, Map<String, Object> view) {
        String text = load(name);
        Mustache mustache = new DefaultMustacheFactory().compile(new StringReader(text), name);
        StringWriter writer = new StringWriter();
        mustache.execute(writer, view);
        return writer.toString();
    }

    private String load(String name) {
        if (promptsDir != null && !promptsDir.isBlank()) {
            Path override = Path.of(promptsDir, name + ".mustache");
            if (Files.exists(override)) {
                try {
                    return Files.readString(override, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        try (InputStream in = PromptTemplates.class.getResourceAsStream("/prompts/" + name + ".mustache")) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        throw new PdlcConfigException("Missing prompt template: " + name);
    }
}
