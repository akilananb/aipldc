package ai.pdlc.core.domain;

/** A CI-produced build artifact reference — tech-stack §4 {@code CiPort}. */
public record ArtifactRef(String name, String url) {
}
