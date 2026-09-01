package ai.pdlc.core.config;

import java.util.Map;

/** {@code pdlc.yaml} §4 {@code board.*} — subset actually consumed by the pilot. */
public record BoardConfig(
        String provider,
        String org,
        String project,
        Map<String, String> types,
        Map<String, String> states,
        AuthConfig auth) {

    public record AuthConfig(String kind, String secretRef) {
    }
}
