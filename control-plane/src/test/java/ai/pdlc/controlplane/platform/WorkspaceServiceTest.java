package ai.pdlc.controlplane.platform;

import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.web.ConflictException;
import ai.pdlc.controlplane.web.ForbiddenException;
import ai.pdlc.controlplane.web.NotFoundException;
import ai.pdlc.controlplane.web.dto.WorkspaceDto;
import ai.pdlc.controlplane.web.dto.WorkspaceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceServiceTest {

    static final Identity ENTERPRISE_ADMIN = new Identity("it@acme", "Admin");
    static final Identity ENG_ADMIN = new Identity("eng-lead@acme", "SquadLead");
    static final Identity FIN_ADMIN = new Identity("fin-lead@acme", "SquadLead");

    private final InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
    private final WorkspaceService service = new WorkspaceService(store);

    @BeforeEach
    void seed() {
        service.create(new WorkspaceRequest("engineering", "Engineering", List.of(ENG_ADMIN.user())), ENTERPRISE_ADMIN);
        service.create(new WorkspaceRequest("finance", "Finance", List.of(FIN_ADMIN.user())), ENTERPRISE_ADMIN);
    }

    @Test
    void onlyAnEnterpriseAdminCreatesWorkspaces() {
        assertThatThrownBy(() -> service.create(new WorkspaceRequest("ops", "Ops", List.of("x")), ENG_ADMIN))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void creationRequiresAValidIdAndAtLeastOneAdmin() {
        assertThatThrownBy(() -> service.create(new WorkspaceRequest("Bad Id", "", List.of()), ENTERPRISE_ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id must match")
                .hasMessageContaining("name is required")
                .hasMessageContaining("at least one workspace admin");
        assertThatThrownBy(() -> service.create(new WorkspaceRequest("finance", "Dup", List.of("x")), ENTERPRISE_ADMIN))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void membersSeeOnlyTheirWorkspacesAndEnterpriseAdminSeesAllNames() {
        assertThat(service.list(ENG_ADMIN)).extracting(WorkspaceDto::id).containsExactly("engineering");
        assertThat(service.list(ENTERPRISE_ADMIN)).extracting(WorkspaceDto::id).containsExactly("engineering", "finance");
    }

    @Test
    void aNonMemberCannotTellAnotherWorkspaceFromAMissingOne() {
        assertThatThrownBy(() -> service.get("finance", ENG_ADMIN))
                .isInstanceOf(NotFoundException.class).hasMessage("No workspace finance");
        assertThatThrownBy(() -> service.get("nope", ENG_ADMIN))
                .isInstanceOf(NotFoundException.class).hasMessage("No workspace nope");
        // Creating a workspace is not a membership of it.
        assertThatThrownBy(() -> service.members("finance", ENTERPRISE_ADMIN)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void workspaceAdminsManageMembersAndOthersCannot() {
        service.setMember("engineering", "author@acme", EnumSet.of(Capability.AUTHOR), ENG_ADMIN);
        Identity author = new Identity("author@acme", "FSDeveloper");

        assertThat(service.get("engineering", author).capabilities()).containsExactly(Capability.AUTHOR);
        assertThatThrownBy(() -> service.setMember("engineering", "x@acme", Set.of(Capability.AUTHOR), author))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.setMember("engineering", "x@acme", Set.of(Capability.AUTHOR), FIN_ADMIN))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void theLastWorkspaceAdminCannotBeRemoved() {
        assertThatThrownBy(() -> service.setMember("engineering", ENG_ADMIN.user(), Set.of(Capability.AUTHOR), ENG_ADMIN))
                .isInstanceOf(ConflictException.class);

        service.setMember("engineering", "second@acme", EnumSet.of(Capability.WORKSPACE_ADMIN), ENG_ADMIN);
        service.setMember("engineering", ENG_ADMIN.user(), Set.of(), ENG_ADMIN);

        assertThatThrownBy(() -> service.get("engineering", ENG_ADMIN)).isInstanceOf(NotFoundException.class);
    }
}
