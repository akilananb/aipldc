package ai.pdlc.core.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolArgsTest {

    @Test
    void acceptsDeclaredArgumentsAndHashesThemCanonically() {
        ToolArgs.Parsed a = ToolArgs.parse("{\"orderId\":\"o-1\",\"expand\":true}", ToolSpecValidatorTest.valid().inputSchema());
        ToolArgs.Parsed b = ToolArgs.parse("{ \"expand\": true, \"orderId\": \"o-1\" }", ToolSpecValidatorTest.valid().inputSchema());

        assertThat(a.valid()).isTrue();
        assertThat(a.hash()).startsWith("sha256:").isEqualTo(b.hash());
    }

    @Test
    void rejectsMissingWrongTypedAndUndeclaredArguments() {
        ToolArgs.Parsed p = ToolArgs.parse("{\"expand\":\"yes\",\"admin\":true}", ToolSpecValidatorTest.valid().inputSchema());

        assertThat(p.valid()).isFalse();
        assertThat(p.errors()).contains("$.admin is not a declared argument");
        assertThat(p.errors()).anyMatch(e -> e.contains("orderId"));
        assertThat(p.errors()).anyMatch(e -> e.contains("expand"));
    }

    @Test
    void rejectsMalformedJsonWithoutEchoingIt() {
        ToolArgs.Parsed p = ToolArgs.parse("{\"orderId\": sk-secret", ToolSpecValidatorTest.valid().inputSchema());

        assertThat(p.errors()).containsExactly("arguments are not valid JSON");
        assertThat(p.hash()).isNull();
    }

    @Test
    void blankArgumentsMeanAnEmptyObject() {
        ToolArgs.Parsed p = ToolArgs.parse("", java.util.Map.of("type", "object", "properties", java.util.Map.of()));

        assertThat(p.valid()).isTrue();
    }
}
