package ai.pdlc.controlplane.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Plain unit test for {@link ItemsController#gateForStep} — the pure step-name-to-gate-role
 * mapping {@code POST /api/items/{id}/retry} uses to decide who may retry a blocked step. */
class ItemsControllerGateForStepTest {

    @Test
    void releaseDraftRequiresGate3() {
        assertThat(ItemsController.gateForStep("release-draft")).isEqualTo("G3");
    }

    @Test
    void reviewStoryRequiresGate2() {
        assertThat(ItemsController.gateForStep("review-story")).isEqualTo("G2");
    }

    @Test
    void everyPreReviewStepRequiresGate1() {
        assertThat(ItemsController.gateForStep("grill-evaluate")).isEqualTo("G1");
        assertThat(ItemsController.gateForStep("grill-next-round")).isEqualTo("G1");
        assertThat(ItemsController.gateForStep("po-draft")).isEqualTo("G1");
        assertThat(ItemsController.gateForStep("po-revise")).isEqualTo("G1");
        assertThat(ItemsController.gateForStep("evaluate-quality")).isEqualTo("G1");
        assertThat(ItemsController.gateForStep("plan-next-step")).isEqualTo("G1");
        assertThat(ItemsController.gateForStep("plan-consult")).isEqualTo("G1");
    }
}
