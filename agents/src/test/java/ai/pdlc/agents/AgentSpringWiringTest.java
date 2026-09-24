package ai.pdlc.agents;

import ai.pdlc.agents.config.PortRegistry;
import ai.pdlc.agents.grill.GrillAgent;
import ai.pdlc.agents.grill.GrillSkills;
import ai.pdlc.agents.plan.PlanAgent;
import ai.pdlc.agents.po.PoAgent;
import ai.pdlc.agents.quality.QualityAgent;
import ai.pdlc.agents.release.ReleaseAgent;
import ai.pdlc.agents.review.ReviewAgent;
import ai.pdlc.agents.templates.PromptTemplates;
import ai.pdlc.core.config.AgentsConfig;
import ai.pdlc.core.config.BoardConfig;
import ai.pdlc.core.config.NotifyConfig;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.ProjectDirectory;
import ai.pdlc.core.config.ProjectMeta;
import ai.pdlc.core.config.RepoConfig;
import com.embabel.agent.api.common.Ai;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import ai.pdlc.agents.platform.AgentRunActivitiesImpl;
import ai.pdlc.agents.platform.OpenAiCompatibleModelInvoker;
import ai.pdlc.agents.platform.ToolExecutor;
import ai.pdlc.agents.platform.RunStore;
import ai.pdlc.core.port.SecretsPort;

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
        ProjectDirectory projectDirectory() {
            return mock(ProjectDirectory.class);
        }

        @Bean
        PortRegistry portRegistry() {
            return mock(PortRegistry.class);
        }

        @Bean
        RunStore runStore() {
            return mock(RunStore.class);
        }

        @Bean
        ai.pdlc.agents.platform.ToolStore toolStore() {
            return mock(ai.pdlc.agents.platform.ToolStore.class);
        }

        @Bean
        ai.pdlc.core.platform.EgressPolicy egressPolicy() {
            return new ai.pdlc.core.platform.EgressPolicy(java.util.Set.of());
        }

        @Bean
        ai.pdlc.core.port.NotifyPort notifyPort() {
            return mock(ai.pdlc.core.port.NotifyPort.class);
        }

        @Bean
        SecretsPort secretsPort() {
            return mock(SecretsPort.class);
        }

        @Bean
        Profile activeProfile() {
            return new Profile("local",
                    new ProjectMeta("local", "local", null, List.of(), "", null),
                    new BoardConfig("in-memory", null, null, Map.of(), Map.of(), null),
                    List.of(new RepoConfig("main", "in-memory", "local://x", "main", "openspec", List.of(), true)),
                    new NotifyConfig("none", "none"),
                    new AgentsConfig("http://stub", null, Map.of()),
                    Map.of());
        }
    }

    /** {@link AgentRunActivitiesImpl} and {@link ToolExecutor} also have two constructors (public + clock-injecting for tests). */
    @Test
    void springContainerConstructsThePlatformRunner() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(TestBeans.class, OpenAiCompatibleModelInvoker.class, ai.pdlc.agents.platform.McpCredentials.class,
                    ai.pdlc.agents.platform.McpToolCaller.class, ToolExecutor.class, AgentRunActivitiesImpl.class,
                    ai.pdlc.agents.platform.McpDiscoveryActivitiesImpl.class, ai.pdlc.agents.config.SandboxConfig.class);
            ctx.refresh();

            // Sandbox tools default to off: every call is refused (no isolation runtime).
            assertThat(ctx.getBean(ai.pdlc.agents.platform.SandboxToolRunner.class).isolation()).isNull();
            assertThat(ctx.getBean(AgentRunActivitiesImpl.class)).isNotNull();
            assertThat(ctx.getBean(ai.pdlc.agents.platform.McpDiscoveryActivitiesImpl.class)).isNotNull();
        }
    }

    @Test
    void springContainerConstructsPromptTemplatesAndAllFourAgentsViaConstructorInjection() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(TestBeans.class, PromptTemplates.class, GrillSkills.class, GrillAgent.class, PoAgent.class, ReviewAgent.class, ReleaseAgent.class, QualityAgent.class, PlanAgent.class);
            ctx.refresh();

            assertThat(ctx.getBean(PromptTemplates.class)).isNotNull();
            assertThat(ctx.getBean(GrillAgent.class)).isNotNull();
            assertThat(ctx.getBean(PoAgent.class)).isNotNull();
            assertThat(ctx.getBean(ReviewAgent.class)).isNotNull();
            assertThat(ctx.getBean(QualityAgent.class)).isNotNull();
            assertThat(ctx.getBean(PlanAgent.class)).isNotNull();

            // Prove it's the Profile-taking constructor that won (not an accidental default), and
            // that the rendered prompt actually flows through the classpath-default template.
            PromptTemplates templates = ctx.getBean(PromptTemplates.class);
            assertThat(templates.render("grill-questions", Map.of("title", "X", "description", "Y")))
                    .startsWith("[agent:grill]");
        }
    }
}
