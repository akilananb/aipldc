package ai.pdlc.core.domain;

import java.time.Instant;

/**
 * A signature: {@code { who (OIDC sub), role, stage, version, contentHash, at }} — tech-stack §3.3.
 * {@code contentHash} is the sha256 hex of the artifact content at {@code version}; the UI shows the
 * hash so a checker can prove what they signed.
 */
public record Approval(String who, String role, String stage, int version, String contentHash, Instant at) {
}
