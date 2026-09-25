package ai.pdlc.agents.platform;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Map;

/**
 * Renders a published agent prompt (Mustache text pinned in the run's version, not a file) with a
 * data-only view of the run inputs. Prompts are not HTML, so {@code {{x}}} inserts the value
 * verbatim, same as {@code {{{x}}}}. Partials and delimiter changes never reach here -
 * {@code AgentSpecValidator} rejects them at publication.
 */
final class PromptRenderer {

    private static final DefaultMustacheFactory FACTORY = new DefaultMustacheFactory() {
        @Override
        public void encode(String value, Writer writer) {
            try {
                writer.write(value);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    };

    private PromptRenderer() {
    }

    static String render(String name, String template, Map<String, String> inputs) {
        Mustache mustache = FACTORY.compile(new StringReader(template), name);
        StringWriter out = new StringWriter();
        mustache.execute(out, Map.<String, Object>copyOf(inputs));
        return out.toString();
    }
}
