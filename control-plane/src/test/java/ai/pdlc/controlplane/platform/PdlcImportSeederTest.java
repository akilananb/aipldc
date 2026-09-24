package ai.pdlc.controlplane.platform;

import ai.pdlc.controlplane.connections.InMemoryConnectionStore;
import ai.pdlc.controlplane.connections.ModelCatalog;
import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.web.dto.AgentDefinitionDto;
import ai.pdlc.controlplane.web.dto.AgentDraftRequest;
import ai.pdlc.controlplane.web.dto.AgentVersionDto;
import ai.pdlc.core.config.AgentsConfig;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.ProjectDirectory;
import ai.pdlc.core.platform.AgentSpec;
import ai.pdlc.core.platform.BundledPrompts;
import ai.pdlc.core.platform.ContentHash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The one-time PDLC package import (docs/phase-1-execution-spec.md slice 6) over in-memory stores. */
class PdlcImportSeederTest {

    static final Identity ADMIN = new Identity("admin@acme", "Admin");

    private final InMemoryWorkspaceStore workspaceStore = new InMemoryWorkspaceStore();
    private final InMemoryConnectionStore connections = InMemoryConnectionStore.withModels("default-gateway", "sonnet");
    private final WorkspaceService workspaces = new WorkspaceService(workspaceStore);
    private final AgentRegistryService registry =
            new AgentRegistryService(new InMemoryAgentRegistryStore(), workspaces, new ModelCatalog(connections));
    private final ProjectDirectory projects = mock(ProjectDirectory.class);

    private PdlcImportSeeder seeder(String promptsDir, String admins) {
        Map<String, AgentsConfig.RoleConfig> roles = new LinkedHashMap<>();
        for (String role : List.of("grill", "po", "plan", "review", "release", "mention")) {
            roles.put(role, new AgentsConfig.RoleConfig("sonnet", role.equals("po") ? 40_000L : null));
        }
        // no "quality" role: quality-eval has no model and must stay a draft
        Profile profile = mock(Profile.class);
        when(profile.agents()).thenReturn(new AgentsConfig("https://gateway.example/v1", promptsDir, roles));
        Profile any = mock(Profile.class);
        when(projects.projects()).thenReturn(Map.of("local", any, "restaurant", any));
        return new PdlcImportSeeder(workspaceStore, registry, connections, projects, profile, admins);
    }

    @Test
    void importsEveryBundledPromptAsPublishedV1ExceptOnesThatFailPublication() {
        seeder(null, "admin@acme").run(null);

        List<AgentDefinitionDto> agents = registry.list("pdlc", ADMIN);
        assertThat(agents).extracting(AgentDefinitionDto::id).containsExactlyInAnyOrderElementsOf(BundledPrompts.NAMES);
        assertThat(agents).filteredOn(a -> a.currentVersion() != null).hasSize(BundledPrompts.NAMES.size() - 1);
        AgentDefinitionDto quality = registry.get("pdlc", "quality-eval", ADMIN);
        assertThat(quality.currentVersion()).isNull();
        assertThat(connections.imports().get("pdlc-package-v1")).contains("quality-eval=[model.model is required]");

        AgentVersionDto po = registry.version("pdlc", "po-draft", 1, ADMIN);
        String bundled = BundledPrompts.load("po-draft", null).orElseThrow().text();
        assertThat(po.spec().prompt()).isEqualTo(bundled);
        assertThat(po.name()).isEqualTo("Po draft");
        assertThat(po.contentHash()).isEqualTo(ContentHash.ofAgent("Po draft", po.spec()));
        assertThat(po.spec().limits()).isEqualTo(new AgentSpec.Limits(40_000, 600));
        assertThat(po.spec().variables()).isNotEmpty().allMatch(v -> !v.required());
        assertThat(po.publishedBy()).isEqualTo("system:pdlc-import");
    }

    @Test
    void linksExistingProjectsAndGrantsConfiguredAdmins() {
        seeder(null, "admin@acme, lead@acme").run(null);

        assertThat(workspaceStore.projects("pdlc")).extracting(WorkspaceStore.LinkedProject::id)
                .containsExactly("local", "restaurant");
        assertThat(workspaceStore.capabilities("pdlc", "lead@acme")).containsExactly(Capability.WORKSPACE_ADMIN);
    }

    @Test
    void aSecondBootImportsNothingAndKeepsStudioEdits() {
        seeder(null, "admin@acme").run(null);
        AgentDefinitionDto grill = registry.get("pdlc", "grill-questions", ADMIN);
        AgentSpec edited = new AgentSpec(grill.draftSpec().description(), "native", "Edited in the Studio", List.of(),
                grill.draftSpec().model(), grill.draftSpec().limits(), null);
        registry.saveDraft("pdlc", "grill-questions", new AgentDraftRequest(null, grill.draftName(), edited, grill.draftRevision()), ADMIN);
        registry.publish("pdlc", "grill-questions", grill.draftRevision() + 1, ADMIN);

        seeder(null, "admin@acme").run(null);

        assertThat(registry.get("pdlc", "grill-questions", ADMIN).currentVersion()).isEqualTo(2);
        assertThat(registry.versions("pdlc", "grill-questions", ADMIN)).hasSize(2);
        // ...and the bundled file the legacy pipeline renders is untouched.
        assertThat(BundledPrompts.load("grill-questions", null).orElseThrow().text()).doesNotContain("Edited in the Studio");
    }

    @Test
    void adminGrantsAreAdditiveAndReappliedOnEveryBoot() {
        seeder(null, "").run(null);
        assertThat(workspaceStore.members("pdlc")).isEmpty();

        seeder(null, "lead@acme").run(null);
        workspaceStore.setMember("pdlc", "lead@acme", java.util.EnumSet.of(Capability.WORKSPACE_ADMIN, Capability.OPERATOR), "lead@acme");
        seeder(null, "lead@acme").run(null);

        assertThat(workspaceStore.capabilities("pdlc", "lead@acme"))
                .containsExactlyInAnyOrder(Capability.WORKSPACE_ADMIN, Capability.OPERATOR);
    }

    @Test
    void aReadableOverrideDirWinsOverTheBundledPrompt(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("review-summary.mustache"), "Team override for {{change}}");

        seeder(dir.toString(), "admin@acme").run(null);

        AgentVersionDto review = registry.version("pdlc", "review-summary", 1, ADMIN);
        assertThat(review.spec().prompt()).isEqualTo("Team override for {{change}}");
        assertThat(review.spec().variables()).extracting(AgentSpec.Variable::name).containsExactly("change");
        assertThat(connections.imports().get("pdlc-package-v1")).contains("review-summary=override:");
    }
}
