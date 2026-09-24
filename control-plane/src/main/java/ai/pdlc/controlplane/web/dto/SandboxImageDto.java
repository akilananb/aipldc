package ai.pdlc.controlplane.web.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** An enterprise-approved sandbox image: digest-pinned, with its declared schemas, reachable hosts and limits. */
public record SandboxImageDto(String id, String imageRef, String description, Map<String, Object> inputSchema,
                              Map<String, Object> outputSchema, List<String> egressHosts, int cpuMillis, int memoryMb,
                              int timeoutSeconds, String status, OffsetDateTime createdAt, String createdBy,
                              OffsetDateTime updatedAt, String updatedBy, OffsetDateTime retiredAt, String retiredBy) {
}
