package ai.pdlc.controlplane.platform;

import ai.pdlc.controlplane.connections.InMemoryConnectionStore;
import ai.pdlc.controlplane.web.ConflictException;
import ai.pdlc.controlplane.web.ForbiddenException;
import ai.pdlc.controlplane.web.dto.AgentDraftRequest;
import ai.pdlc.controlplane.web.dto.SandboxImageDto;
import ai.pdlc.controlplane.web.dto.SandboxImageRequest;
import ai.pdlc.controlplane.web.dto.ToolDefinitionDto;
import ai.pdlc.controlplane.web.dto.ToolDraftRequest;
import ai.pdlc.controlplane.web.dto.WorkspaceRequest;
import ai.pdlc.core.platform.AgentSpec;
import ai.pdlc.core.platform.ToolSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static ai.pdlc.controlplane.platform.AgentRegistryServiceTest.AUTHOR;
import static ai.pdlc.controlplane.platform.AgentRegistryServiceTest.OPERATOR;
import static ai.pdlc.controlplane.platform.AgentRegistryServiceTest.labelSpec;
import static ai.pdlc.controlplane.platform.WorkspaceServiceTest.ENG_ADMIN;
import static ai.pdlc.controlplane.platform.WorkspaceServiceTest.ENTERPRISE_ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The sandbox image catalog and the registry rules for {@code kind: sandbox} tools (docs/phase-2-execution-spec.md slice 2.4). */
class SandboxToolRegistryTest {

    static final String REF_V1 = "registry.acme/tools/order-report@sha256:" + "a".repeat(64);
    static final String REF_V2 = "registry.acme/tools/order-report@sha256:" + "b".repeat(64);
    static final Map<String, Object> INPUT = Map.of("type", "object",
            "properties", Map.of("orderId", Map.of("type", "string")), "required", List.of("orderId"));

    private final WorkspaceService workspaces = new WorkspaceService(new InMemoryWorkspaceStore());
    private final TestRegistries.Registries r = TestRegistries.over(workspaces, InMemoryConnectionStore.withModels("gw", "sonnet"));

    @BeforeEach
    void seed() {
        workspaces.create(new WorkspaceRequest("engineering", "Engineering", List.of(ENG_ADMIN.user())), ENTERPRISE_ADMIN);
        workspaces.setMember("engineering", AUTHOR.user(), EnumSet.of(Capability.AUTHOR), ENG_ADMIN);
        workspaces.setMember("engineering", OPERATOR.user(), EnumSet.of(Capability.OPERATOR), ENG_ADMIN);
    }

    static SandboxImageRequest image(String ref) {
        return new SandboxImageRequest("order-report", ref, "Builds an order report", INPUT,
                Map.of("type", "object", "properties", Map.of("report", Map.of("type", "string"))),
                List.of("api.example", "files.example:8443"), 500, 256, 60);
    }

    static ToolSpec tool(String ref, int timeout) {
        return new ToolSpec("Build an order report", "sandbox", null, null, null, INPUT, "READ", timeout, 65_536,
                null, null, null, null, "order-report", ref);
    }

    @Test
    void onlyTheEnterpriseAdminCuratesTheCatalogAndEveryoneCanListIt() {
        assertThatThrownBy(() -> r.sandboxImages().create(image(REF_V1), ENG_ADMIN)).isInstanceOf(ForbiddenException.class);

        SandboxImageDto created = r.sandboxImages().create(image(REF_V1), ENTERPRISE_ADMIN);

        assertThat(created.status()).isEqualTo("ACTIVE");
        assertThat(created.egressHosts()).containsExactly("api.example", "files.example:8443");
        assertThat(created.inputSchema()).isEqualTo(INPUT);
        assertThat(r.sandboxImages().list()).extracting(SandboxImageDto::id).containsExactly("order-report");
        assertThatThrownBy(() -> r.sandboxImages().create(image(REF_V1), ENTERPRISE_ADMIN)).isInstanceOf(ConflictException.class);
    }

    @Test
    void catalogEntriesPinADigestAndDeclareBoundedLimits() {
        SandboxImageRequest bad = new SandboxImageRequest("Bad_Id", "registry.acme/tools/x:latest", " ",
                Map.of("type", "array"), Map.of("type", "object", "oneOf", List.of()), List.of("10.0.0.1/8", "API.example"),
                10, 1_000_000, 601);

        assertThatThrownBy(() -> r.sandboxImages().create(bad, ENTERPRISE_ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id must match")
                .hasMessageContaining("imageRef must be a digest-pinned image reference")
                .hasMessageContaining("description is required")
                .hasMessageContaining("inputSchema must be a schema with type object")
                .hasMessageContaining("outputSchema: ")
                .hasMessageContaining("egress host 10.0.0.1/8")
                .hasMessageContaining("egress host API.example")
                .hasMessageContaining("cpuMillis must be between")
                .hasMessageContaining("memoryMb must be between")
                .hasMessageContaining("timeoutSeconds must be between 1 and 600");
    }

    @Test
    void aSandboxToolPublishesOnlyAgainstAnActiveEntryWithTheReviewedDigestAndSchema() {
        r.sandboxImages().create(image(REF_V1), ENTERPRISE_ADMIN);
        ToolDefinitionDto created = r.tools().create("engineering",
                new ToolDraftRequest("order-report", "Order report", tool(REF_V2, 120), null), AUTHOR);

        assertThat(r.tools().validate("engineering", "order-report", AUTHOR).errors()).containsExactly(
                "sandbox image order-report now pins " + REF_V1 + "; the tool was reviewed with " + REF_V2 + " and needs re-review",
                "timeoutSeconds may not exceed the image's limit of 60");

        ToolSpec otherSchema = new ToolSpec("x", "sandbox", null, null, null, Map.of("type", "object"), "READ", 30, 1000,
                null, null, null, null, "order-report", REF_V1);
        r.tools().saveDraft("engineering", "order-report", new ToolDraftRequest(null, "Order report", otherSchema,
                created.draftRevision()), AUTHOR);
        assertThat(r.tools().validate("engineering", "order-report", AUTHOR).errors())
                .containsExactly("inputSchema must be the input schema declared by sandbox image order-report");

        ToolDefinitionDto fixed = r.tools().saveDraft("engineering", "order-report",
                new ToolDraftRequest(null, "Order report", tool(REF_V1, 60), created.draftRevision() + 1), AUTHOR);
        assertThat(r.tools().publish("engineering", "order-report", fixed.draftRevision(), ENG_ADMIN).version()).isEqualTo(1);
    }

    @Test
    void aNewDigestOrRetiringTheImageStopsAgentsThatPinTheTool() {
        r.sandboxImages().create(image(REF_V1), ENTERPRISE_ADMIN);
        ToolDefinitionDto created = r.tools().create("engineering",
                new ToolDraftRequest("order-report", "Order report", tool(REF_V1, 60), null), AUTHOR);
        r.tools().publish("engineering", "order-report", created.draftRevision(), ENG_ADMIN);
        AgentSpec base = labelSpec("T");
        var agent = r.agents().create("engineering", new AgentDraftRequest("bot", "Bot", new AgentSpec(base.description(),
                base.runtime(), base.prompt(), base.variables(), base.model(), base.limits(), base.outputSchema(),
                List.of(new AgentSpec.ToolRef("order-report", 1))), null), AUTHOR);
        r.agents().publish("engineering", "bot", agent.draftRevision(), ENG_ADMIN);
        assertThat(r.agents().resolveForRun("engineering", "bot", OPERATOR).definition().version()).isEqualTo(1);

        r.sandboxImages().update("order-report", image(REF_V2), ENTERPRISE_ADMIN);
        assertThatThrownBy(() -> r.agents().resolveForRun("engineering", "bot", OPERATOR))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("tool order-report v1: sandbox image order-report now pins " + REF_V2);

        r.sandboxImages().update("order-report", image(REF_V1), ENTERPRISE_ADMIN);
        r.sandboxImages().retire("order-report", ENTERPRISE_ADMIN);
        assertThatThrownBy(() -> r.agents().resolveForRun("engineering", "bot", OPERATOR))
                .hasMessageContaining("tool order-report v1: sandbox image order-report is retired");
        assertThatThrownBy(() -> r.sandboxImages().update("order-report", image(REF_V1), ENTERPRISE_ADMIN))
                .isInstanceOf(ConflictException.class);
    }
}
