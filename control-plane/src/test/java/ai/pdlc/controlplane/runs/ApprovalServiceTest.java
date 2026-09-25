package ai.pdlc.controlplane.runs;

import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.platform.Capability;
import ai.pdlc.controlplane.platform.InMemoryWorkspaceStore;
import ai.pdlc.controlplane.platform.WorkspaceService;
import ai.pdlc.controlplane.web.ConflictException;
import ai.pdlc.controlplane.web.ForbiddenException;
import ai.pdlc.controlplane.web.NotFoundException;
import ai.pdlc.controlplane.web.dto.WorkspaceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Write approvals and effect resolution (docs/phase-2-execution-spec.md slice 2.2). */
class ApprovalServiceTest {

    static final Identity ADMIN = new Identity("it@acme", "Admin");
    static final Identity LEAD = new Identity("lead@acme", "SquadLead");
    static final Identity OPERATOR = new Identity("op@acme", "QA");
    static final Identity REVIEWER = new Identity("reviewer@acme", "PO");
    static final Identity FIN = new Identity("fin@acme", "SquadLead");

    private final WorkspaceService workspaces = new WorkspaceService(new InMemoryWorkspaceStore());
    private final InMemoryApprovalStore store = new InMemoryApprovalStore();
    private final RunServiceTest.FakeLauncher launcher = new RunServiceTest.FakeLauncher();
    private final ApprovalService service = new ApprovalService(store, launcher, workspaces);
    private final UUID run = UUID.randomUUID();

    @BeforeEach
    void seed() {
        workspaces.create(new WorkspaceRequest("ops", "Ops", List.of(LEAD.user())), ADMIN);
        workspaces.create(new WorkspaceRequest("finance", "Finance", List.of(FIN.user())), ADMIN);
        workspaces.setMember("ops", OPERATOR.user(), EnumSet.of(Capability.OPERATOR, Capability.REVIEWER), LEAD);
        workspaces.setMember("ops", REVIEWER.user(), EnumSet.of(Capability.REVIEWER), LEAD);
    }

    @Test
    void aReviewerWhoDidNotStartTheRunApprovesAndTheRunIsSignalled() {
        var approval = store.add("ops", run, OPERATOR.user());

        var decided = service.approve("ops", approval.id(), "ok to cancel", REVIEWER);

        assertThat(decided.status()).isEqualTo("APPROVED");
        assertThat(decided.decidedBy()).isEqualTo(REVIEWER.user());
        assertThat(decided.reason()).isEqualTo("ok to cancel");
        assertThat(launcher.signals).containsExactly("approval|agent-run-" + run + "|" + approval.id());
    }

    @Test
    void theRunsOwnStarterCannotApproveItsWritesEvenAsAReviewer() {
        var approval = store.add("ops", run, OPERATOR.user());

        assertThatThrownBy(() -> service.approve("ops", approval.id(), null, OPERATOR))
                .isInstanceOf(ForbiddenException.class).hasMessageContaining("cannot decide its writes");
        assertThat(store.approvals.get(approval.id()).status()).isEqualTo("PENDING");
        assertThat(launcher.signals).isEmpty();
    }

    @Test
    void onlyReviewersDecideAndOnlyOnce() {
        var approval = store.add("ops", run, OPERATOR.user());

        assertThatThrownBy(() -> service.approve("ops", approval.id(), null, LEAD)).isInstanceOf(ForbiddenException.class);
        service.reject("ops", approval.id(), "wrong order", REVIEWER);
        assertThatThrownBy(() -> service.approve("ops", approval.id(), null, REVIEWER)).isInstanceOf(ConflictException.class);
        assertThat(store.approvals.get(approval.id()).status()).isEqualTo("REJECTED");
    }

    @Test
    void anotherWorkspaceCannotSeeOrDecideTheApproval() {
        var approval = store.add("ops", run, OPERATOR.user());

        assertThatThrownBy(() -> service.list("ops", null, FIN)).isInstanceOf(NotFoundException.class);
        assertThat(service.list("finance", null, FIN)).isEmpty();
        workspaces.setMember("finance", "fin-reviewer@acme", EnumSet.of(Capability.REVIEWER), FIN);
        assertThatThrownBy(() -> service.approve("finance", approval.id(), null, new Identity("fin-reviewer@acme", "PO")))
                .isInstanceOf(NotFoundException.class);
        assertThat(service.list("ops", "PENDING", REVIEWER)).hasSize(1);
        assertThatThrownBy(() -> service.list("ops", "MAYBE", REVIEWER)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFailedSignalStillRecordsTheDecision() {
        var approval = store.add("ops", run, OPERATOR.user());
        launcher.failure = new IllegalStateException("temporal down");

        assertThat(service.approve("ops", approval.id(), null, REVIEWER).status()).isEqualTo("APPROVED");
    }

    @Test
    void anOperatorResolvesOnlyUnknownEffectsOfTheirWorkspacesRuns() {
        store.add("ops", run, LEAD.user());
        var unknown = store.addEffect(run, "UNKNOWN");
        var done = store.addEffect(run, "SUCCEEDED");

        assertThatThrownBy(() -> service.resolve("ops", run.toString(), unknown.id(), "SUCCEEDED", null, REVIEWER))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.resolve("ops", run.toString(), unknown.id(), "MAYBE", null, OPERATOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resolve("ops", run.toString(), done.id(), "RETRY", null, OPERATOR))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.effects("finance", run.toString(), FIN)).isInstanceOf(NotFoundException.class);

        var resolved = service.resolve("ops", run.toString(), unknown.id(), "SUCCEEDED", "confirmed in console", OPERATOR);

        assertThat(resolved.state()).isEqualTo("SUCCEEDED");
        assertThat(resolved.resolvedBy()).isEqualTo(OPERATOR.user());
        assertThat(launcher.signals).containsExactly("effect|agent-run-" + run + "|" + unknown.id());
        assertThat(service.effects("ops", run.toString(), REVIEWER)).hasSize(2);
    }

    @Test
    void retryPutsTheEffectBackToIntended() {
        store.add("ops", run, LEAD.user());
        var unknown = store.addEffect(run, "UNKNOWN");

        assertThat(service.resolve("ops", run.toString(), unknown.id(), "RETRY", null, OPERATOR).state()).isEqualTo("INTENDED");
    }
}
