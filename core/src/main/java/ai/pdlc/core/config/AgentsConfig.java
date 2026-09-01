package ai.pdlc.core.config;

import java.util.Map;

/** {@code pdlc.yaml} §4 {@code agents.*} — {@code gateway} + {@code roles.grill|po} consumed by the pilot. */
public record AgentsConfig(String gateway, Map<String, RoleConfig> roles) {

    public record RoleConfig(String model, Long budgetTokens) {
    }
}
