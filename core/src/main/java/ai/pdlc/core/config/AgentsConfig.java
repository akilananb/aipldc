package ai.pdlc.core.config;

import java.util.Map;

/** {@code pdlc.yaml} §4 {@code agents.*} — {@code gateway} + {@code roles.grill|po} consumed by the pilot.
 * {@code promptsDir}: optional directory of {@code *.mustache} files overriding the bundled prompt
 * templates ({@code agents.prompts_dir}); {@code null} when absent, meaning bundled defaults apply. */
public record AgentsConfig(String gateway, String promptsDir, Map<String, RoleConfig> roles) {

    public record RoleConfig(String model, Long budgetTokens) {
    }
}
