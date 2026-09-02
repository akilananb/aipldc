package ai.pdlc.agents;

import ai.pdlc.agents.grill.GrillAgent;
import ai.pdlc.agents.po.PoAgent;
import ai.pdlc.agents.quality.QualityAgent;
import ai.pdlc.agents.release.ReleaseAgent;
import ai.pdlc.agents.review.ReviewAgent;
import ai.pdlc.agents.templates.PromptTemplates;
import ai.pdlc.core.config.AgentsConfig;
import ai.pdlc.core.config.BoardConfig;
import ai.pdlc.core.config.NotifyConfig;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.RepoConfig;
import ai.pdlc.core.port.BoardPort;
import ai.pdlc.core.port.RepoPort;
import com.embabel.agent.api.common.Ai;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Proves the Spring container - not just the compiler - can construct {@link PromptTemplates} and
 * the four rewired agents through real constructor injection. This is the one thing the golden
 * {@code PromptTemplatesTest} (which calls the package-private constructor directly, bypassing
 * Spring) and a plain {@code mvn compile} cannot catch: {@link PromptTemplates} has two
 * constructors (public {@code Profile}-taking + package-private {@code String}-taking, "visible
 * for testing"), which is exactly the shape that trips up Spring's implicit constructor-injection
 * resolution when no constructor is annotated {@code @Autowired}. A full {@code @SpringBootTest}
 * boot is not used here - it would need a live Temporal server + Postgres (unavailable in this
 * sandbox, see the pre-existing testcontainers/colima failure in {@code adapters}) - so this wires
 * only the beans agents actually depend on, mirroring how {@code AgentsApplication} assembles them.
 */
class AgentSpringWiringTest {

    @Configuration
    static class TestBeans {
        @Bean
        Ai ai() {
            return mock(Ai.class);
        }

        @Bean
        BoardPort boardPort() {
            return mock(BoardPort.class);
        }

        @Bean
        RepoPort repoPort() {
            return mock(RepoPort.class);
        }

        @Bean
        Profile activeProfile() {
            return new Profile("local",
                    new BoardConfig("in-memory", null, null, Map.of(), Map.of(), null),
                    new RepoConfig("in-memory", "local://x", "main", "openspec"),
                    new NotifyConfig("none", "none"),
                    new AgentsConfig("http://stub", null, Map.of()),
                    Map.of());
        }
    }

    @Test
    void springContainerConstructsPromptTemplatesAndAllFourAgentsViaConstructorInjection() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(TestBeans.class, PromptTemplates.class, GrillAgent.class, PoAgent.class, ReviewAgent.class, ReleaseAgent.class, QualityAgent.class);
            ctx.refresh();

            assertThat(ctx.getBean(PromptTemplates.class)).isNotNull();
            assertThat(ctx.getBean(GrillAgent.class)).isNotNull();
            assertThat(ctx.getBean(PoAgent.class)).isNotNull();
            assertThat(ctx.getBean(ReviewAgent.class)).isNotNull();
            assertThat(ctx.getBean(ReleaseAgent.class)).isNotNull();
            assertThat(ctx.getBean(QualityAgent.class)).isNotNull();

            // Prove it's the Profile-taking constructor that won (not an accidental default), and
            // that the rendered prompt actually flows through the classpath-default template.
            PromptTemplates templates = ctx.getBean(PromptTemplates.class);
            assertThat(templates.render("grill-questions", Map.of("title", "X", "description", "Y")))
                    .startsWith("[agent:grill]");
        }
    }
}
