package ai.pdlc.controlplane.web.dto;

import java.util.List;
import java.util.Map;

/** Create or update a sandbox catalog entry (enterprise Admin). {@code id} is ignored on update. */
public record SandboxImageRequest(String id, String imageRef, String description, Map<String, Object> inputSchema,
                                  Map<String, Object> outputSchema, List<String> egressHosts, Integer cpuMillis,
                                  Integer memoryMb, Integer timeoutSeconds) {
}
