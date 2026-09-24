package ai.pdlc.controlplane.platform;

import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.web.ConflictException;
import ai.pdlc.controlplane.web.ForbiddenException;
import ai.pdlc.controlplane.web.NotFoundException;
import ai.pdlc.controlplane.web.dto.AgentDefinitionDto;
import ai.pdlc.controlplane.web.dto.AgentDraftRequest;
import ai.pdlc.controlplane.web.dto.AgentVersionDto;
import ai.pdlc.controlplane.web.dto.WorkspaceRequest;
import ai.pdlc.core.platform.AgentSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static ai.pdlc.controlplane.platform.WorkspaceServiceTest.ENG_ADMIN;
import static ai.pdlc.controlplane.platform.WorkspaceServiceTest.ENTERPRISE_ADMIN;
import static ai.pdlc.controlplane.platform.WorkspaceServiceTest.FIN_ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 1 acceptance "versions and authorization" at the registry level: a run that resolved v1
 * keeps v1 after v2 is published, the next resolution gets v2, and another workspace can neither
 * see nor resolve either version.
 */
class AgentRegistryServiceTest {

    static final Identity AUTHOR = new Identity("author@acme", "FSDeveloper");
    static final Identity OPERATOR = new Identity("operator@acme", "QA");

    private final InMemoryWorkspaceStore workspaceStore = new InMemoryWorkspaceStore();
    private final WorkspaceService workspaces = new WorkspaceService(workspaceStore);
    private final ModelCatalog models = mock(ModelCatalog.class);
    private final AgentRegistryService service =
            new AgentRegistryService(new InMemoryAgentRegistryStore(), workspaces, models);

    @BeforeEach
    void seed() {
        when(models.authorizedModels()).thenReturn(Set.of("sonnet", "haiku"));
        workspaces.create(new WorkspaceRequest("engineering", "Engineering", List.of(ENG_ADMIN.user())), ENTERPRISE_ADMIN);
        workspaces.create(new WorkspaceRequest("finance", "Finance", List.of(FIN_ADMIN.user())), ENTERPRISE_ADMIN);
        workspaces.setMember("engineering", AUTHOR.user(), EnumSet.of(Capability.AUTHOR), ENG_ADMIN);
        workspaces.setMember("engineering", OPERATOR.user(), EnumSet.of(Capability.OPERATOR), ENG_ADMIN);
    }

    static AgentSpec labelSpec(String label) {
        return new AgentSpec("Returns a fixed label", "native", "Return the label " + label + " for {{input}}.",
                List.of(new AgentSpec.Variable("input", null, true)),
                new AgentSpec.ModelBinding("sonnet", List.of()),
                new AgentSpec.Limits(null, 60), null);
    }

    @Test
    void aResolvedRunStaysPinnedToV1AfterV2IsPublished() {
        AgentDefinitionDto created = service.create("engineering",
                new AgentDraftRequest("labeler", "Labeler", labelSpec("ALPHA"), null), AUTHOR);
        service.publish("engineering", "labeler", created.draftRevision(), ENG_ADMIN);
        AgentVersionDto pinnedByRun = service.resolveForRun("engineering", "labeler", OPERATOR);

        AgentDefinitionDto edited = service.saveDraft("engineering", "labeler",
                new AgentDraftRequest(null, "Labeler", labelSpec("BETA"), created.draftRevision()), AUTHOR);
        service.publish("engineering", "labeler", edited.draftRevision(), ENG_ADMIN);
        AgentVersionDto nextRun = service.resolveForRun("engineering", "labeler", OPERATOR);

        assertThat(pinnedByRun.version()).isEqualTo(1);
        assertThat(pinnedByRun.spec().prompt()).contains("ALPHA");
        assertThat(nextRun.version()).isEqualTo(2);
        assertThat(nextRun.spec().prompt()).contains("BETA");
        assertThat(nextRun.contentHash()).isNotEqualTo(pinnedByRun.contentHash());
        // The pinned version is still retrievable byte-for-byte by its number.
        AgentVersionDto v1 = service.version("engineering", "labeler", 1, OPERATOR);
        assertThat(v1.contentHash()).isEqualTo(pinnedByRun.contentHash());
        assertThat(v1.spec()).isEqualTo(pinnedByRun.spec());
    }

    @Test
    void anotherWorkspaceCannotDiscoverReadOrResolveTheAgent() {
        AgentDefinitionDto created = service.create("engineering",
                new AgentDraftRequest("labeler", "Labeler", labelSpec("ALPHA"), null), AUTHOR);
        service.publish("engineering", "labeler", created.draftRevision(), ENG_ADMIN);

        assertThatThrownBy(() -> service.list("engineering", FIN_ADMIN)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.get("engineering", "labeler", FIN_ADMIN)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.version("engineering", "labeler", 1, FIN_ADMIN)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.resolveForRun("engineering", "labeler", FIN_ADMIN)).isInstanceOf(NotFoundException.class);
        // The same id in Finance is a different, unrelated (here: missing) agent.
        assertThatThrownBy(() -> service.get("finance", "labeler", FIN_ADMIN)).isInstanceOf(NotFoundException.class);
        assertThat(service.list("finance", FIN_ADMIN)).isEmpty();
    }

    @Test
    void capabilitiesGateEachLifecycleStep() {
        assertThatThrownBy(() -> service.create("engineering",
                new AgentDraftRequest("x", "X", labelSpec("A"), null), OPERATOR)).isInstanceOf(ForbiddenException.class);
        AgentDefinitionDto created = service.create("engineering",
                new AgentDraftRequest("labeler", "Labeler", labelSpec("ALPHA"), null), AUTHOR);

        assertThatThrownBy(() -> service.publish("engineering", "labeler", created.draftRevision(), AUTHOR))
                .isInstanceOf(ForbiddenException.class);
        service.publish("engineering", "labeler", created.draftRevision(), ENG_ADMIN);
        assertThatThrownBy(() -> service.resolveForRun("engineering", "labeler", AUTHOR))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.retire("engineering", "labeler", AUTHOR))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aStaleDraftRevisionConflictsInsteadOfOverwriting() {
        AgentDefinitionDto created = service.create("engineering",
                new AgentDraftRequest("labeler", "Labeler", labelSpec("ALPHA"), null), AUTHOR);
        service.saveDraft("engineering", "labeler",
                new AgentDraftRequest(null, "Labeler", labelSpec("ONE"), created.draftRevision()), AUTHOR);

        assertThatThrownBy(() -> service.saveDraft("engineering", "labeler",
                new AgentDraftRequest(null, "Labeler", labelSpec("TWO"), created.draftRevision()), ENG_ADMIN))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("revision 2");
        assertThatThrownBy(() -> service.publish("engineering", "labeler", created.draftRevision(), ENG_ADMIN))
                .isInstanceOf(ConflictException.class);
        assertThat(service.get("engineering", "labeler", AUTHOR).draftSpec().prompt()).contains("ONE");
    }

    @Test
    void anInvalidDraftSavesButCannotPublish() {
        AgentSpec bad = new AgentSpec(null, "native", "Hi {{who}}", List.of(),
                new AgentSpec.ModelBinding("gpt-unknown", List.of()), new AgentSpec.Limits(null, 60), null);
        AgentDefinitionDto created = service.create("engineering",
                new AgentDraftRequest("greeter", "Greeter", bad, null), AUTHOR);

        assertThat(service.validate("engineering", "greeter", AUTHOR).errors()).containsExactly(
                "prompt references undeclared variable \"who\"",
                "model \"gpt-unknown\" is not in the authorized model catalog");
        assertThatThrownBy(() -> service.publish("engineering", "greeter", created.draftRevision(), ENG_ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gpt-unknown");
        assertThat(service.versions("engineering", "greeter", AUTHOR)).isEmpty();
        assertThatThrownBy(() -> service.resolveForRun("engineering", "greeter", OPERATOR))
                .isInstanceOf(ConflictException.class).hasMessageContaining("no published version");
    }

    @Test
    void republishingAnUnchangedDraftIsRejected() {
        AgentDefinitionDto created = service.create("engineering",
                new AgentDraftRequest("labeler", "Labeler", labelSpec("ALPHA"), null), AUTHOR);
        service.publish("engineering", "labeler", created.draftRevision(), ENG_ADMIN);

        assertThatThrownBy(() -> service.publish("engineering", "labeler", created.draftRevision(), ENG_ADMIN))
                .isInstanceOf(ConflictException.class).hasMessageContaining("unchanged since published version 1");
    }

    @Test
    void rollbackPointsNewRunsAtAnEarlierVersionWithoutCreatingOne() {
        AgentDefinitionDto created = service.create("engineering",
                new AgentDraftRequest("labeler", "Labeler", labelSpec("ALPHA"), null), AUTHOR);
        service.publish("engineering", "labeler", created.draftRevision(), ENG_ADMIN);
        AgentDefinitionDto edited = service.saveDraft("engineering", "labeler",
                new AgentDraftRequest(null, "Labeler", labelSpec("BETA"), created.draftRevision()), AUTHOR);
        service.publish("engineering", "labeler", edited.draftRevision(), ENG_ADMIN);

        AgentDefinitionDto rolledBack = service.rollback("engineering", "labeler", 1, ENG_ADMIN);

        assertThat(rolledBack.currentVersion()).isEqualTo(1);
        assertThat(rolledBack.latestVersion()).isEqualTo(2);
        assertThat(service.resolveForRun("engineering", "labeler", OPERATOR).spec().prompt()).contains("ALPHA");
        assertThatThrownBy(() -> service.rollback("engineering", "labeler", 7, ENG_ADMIN))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aRetiredAgentKeepsItsVersionsButCannotRunOrChange() {
        AgentDefinitionDto created = service.create("engineering",
                new AgentDraftRequest("labeler", "Labeler", labelSpec("ALPHA"), null), AUTHOR);
        service.publish("engineering", "labeler", created.draftRevision(), ENG_ADMIN);

        service.retire("engineering", "labeler", ENG_ADMIN);

        assertThat(service.version("engineering", "labeler", 1, AUTHOR).spec().prompt()).contains("ALPHA");
        assertThatThrownBy(() -> service.resolveForRun("engineering", "labeler", OPERATOR))
                .isInstanceOf(ConflictException.class).hasMessageContaining("retired");
        assertThatThrownBy(() -> service.saveDraft("engineering", "labeler",
                new AgentDraftRequest(null, "Labeler", labelSpec("B"), created.draftRevision()), AUTHOR))
                .isInstanceOf(ConflictException.class);
    }
}
