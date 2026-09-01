package ai.pdlc.core.config;

/** {@code pdlc.yaml} §4 {@code repo.*} — subset actually consumed by the pilot. */
public record RepoConfig(String provider, String url, String defaultBranch, String specDir) {
}
