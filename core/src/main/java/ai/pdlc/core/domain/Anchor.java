package ai.pdlc.core.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A comment anchor on rendered markdown — tech-stack §3.2. {@code anchorText} is the sha256 hex of
 * the trimmed line text; re-anchoring on a new version matches by {@code anchorText} first, then by
 * {@code scenario}, then falls back to the same line number (caller marks the comment {@code drifted}).
 *
 * @param line       1-based source line number at anchor time
 * @param anchorText sha256 hex of the trimmed text of that line
 * @param nodeType   remark/markdown node type, e.g. {@code heading|paragraph|listItem|code}
 * @param scenario   enclosing "Scenario: <name>" heading, or {@code null}
 */
public record Anchor(int line, String anchorText, String nodeType, String scenario) {

    public static Anchor forLine(int line, String lineText, String nodeType, String scenario) {
        return new Anchor(line, hash(lineText), nodeType, scenario);
    }

    public static String hash(String lineText) {
        String trimmed = lineText == null ? "" : lineText.trim();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(trimmed.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
