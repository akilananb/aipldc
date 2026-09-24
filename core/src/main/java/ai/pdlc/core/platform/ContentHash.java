package ai.pdlc.core.platform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Content identity for published platform assets (configurable-agent-platform.md §2): a
 * {@code sha256:<hex>} digest over canonical JSON - properties and map keys sorted, so the same
 * content always hashes the same regardless of record field order or map insertion order. A run
 * records this hash next to the version number it pinned.
 */
public final class ContentHash {

    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private ContentHash() {
    }

    /** Hash of an agent version: its display name plus its spec. */
    public static String ofAgent(String name, AgentSpec spec) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("name", name);
        content.put("spec", spec);
        return of(content);
    }

    public static String of(Object content) {
        try {
            byte[] json = CANONICAL.writeValueAsBytes(content);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Content is not serializable: " + e.getMessage(), e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Canonical JSON text, used as the stored form of a published version. */
    public static String canonicalJson(Object content) {
        try {
            return CANONICAL.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Content is not serializable: " + e.getMessage(), e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return CANONICAL.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored content is not valid " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }
}
