package ai.pdlc.controlplane.sandbox;

import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.platform.WorkspaceService;
import ai.pdlc.controlplane.sandbox.SandboxImageStore.ImageRow;
import ai.pdlc.controlplane.web.ConflictException;
import ai.pdlc.controlplane.web.ForbiddenException;
import ai.pdlc.controlplane.web.NotFoundException;
import ai.pdlc.controlplane.web.dto.SandboxImageDto;
import ai.pdlc.controlplane.web.dto.SandboxImageRequest;
import ai.pdlc.core.platform.OutputSchema;
import ai.pdlc.core.platform.ToolSpec;
import ai.pdlc.core.platform.ToolSpecValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The enterprise catalog of sandbox images (docs/phase-2-execution-spec.md slice 2.4): the only
 * images a {@code kind: sandbox} tool may run. Entries are curated by the enterprise Admin; any
 * signed-in user may list them to author tools. An entry pins its image by digest and declares the
 * input and output schemas, the hosts the package may reach through the egress proxy and its CPU,
 * memory and time limits - the tool cannot widen any of them.
 *
 * <p>Registry checks here are for authors. The agents worker re-reads the entry at call time and
 * refuses a tool whose entry was retired or whose {@code imageRef} changed since review.
 */
@Service
public class SandboxImageService {

    public static final String ACTIVE = "ACTIVE";
    static final Pattern IMAGE_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,39}$");
    static final Pattern HOST = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,62})(\\.[a-z0-9]([a-z0-9-]{0,62}))*(:[0-9]{1,5})?$");
    static final int MAX_HOSTS = 20;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final TypeReference<List<String>> LIST = new TypeReference<>() { };

    private final SandboxImageStore store;

    public SandboxImageService(SandboxImageStore store) {
        this.store = store;
    }

    /** Any signed-in user (authors pick an image for a tool). */
    public List<SandboxImageDto> list() {
        return store.list().stream().map(SandboxImageService::toDto).toList();
    }

    public SandboxImageDto get(String id) {
        return toDto(store.find(id).orElseThrow(() -> new NotFoundException("No sandbox image " + id)));
    }

    @Transactional
    public SandboxImageDto create(SandboxImageRequest request, Identity identity) {
        requireEnterpriseAdmin(identity);
        List<String> errors = new ArrayList<>();
        if (request.id() == null || !IMAGE_ID.matcher(request.id()).matches()) {
            errors.add("id must match " + IMAGE_ID.pattern());
        }
        validate(request, errors);
        throwIfAny(errors);
        if (!store.insert(row(request.id(), request, identity.user()))) {
            throw new ConflictException("Sandbox image " + request.id() + " already exists");
        }
        return get(request.id());
    }

    /** A new digest (or schema, hosts, limits): tools reviewed against the old image ref stop running until re-reviewed. */
    @Transactional
    public SandboxImageDto update(String id, SandboxImageRequest request, Identity identity) {
        requireEnterpriseAdmin(identity);
        requireActive(id);
        List<String> errors = new ArrayList<>();
        validate(request, errors);
        throwIfAny(errors);
        store.update(row(id, request, identity.user()));
        return get(id);
    }

    @Transactional
    public SandboxImageDto retire(String id, Identity identity) {
        requireEnterpriseAdmin(identity);
        requireActive(id);
        store.retire(id, identity.user());
        return get(id);
    }

    /**
     * Why a {@code kind: sandbox} tool cannot be published or used now (empty = it can): its image
     * must be an active catalog entry still carrying the reviewed {@code sandboxImageRef}, the tool's
     * input schema must be the image's declared one, and its timeout may not exceed the image's.
     */
    public List<String> toolProblems(ToolSpec spec) {
        if (spec.sandboxImage() == null || spec.sandboxImage().isBlank()) {
            return List.of();
        }
        ImageRow image = store.find(spec.sandboxImage()).orElse(null);
        if (image == null) {
            return List.of("sandbox image " + spec.sandboxImage() + " is not in the enterprise catalog");
        }
        if (!ACTIVE.equals(image.status())) {
            return List.of("sandbox image " + spec.sandboxImage() + " is retired");
        }
        List<String> problems = new ArrayList<>();
        if (!image.imageRef().equals(spec.sandboxImageRef())) {
            problems.add("sandbox image " + spec.sandboxImage() + " now pins " + image.imageRef()
                    + "; the tool was reviewed with " + spec.sandboxImageRef() + " and needs re-review");
        }
        if (!Objects.equals(readMap(image.inputSchemaJson()), spec.inputSchema())) {
            problems.add("inputSchema must be the input schema declared by sandbox image " + spec.sandboxImage());
        }
        if (spec.timeoutSeconds() != null && spec.timeoutSeconds() > image.timeoutSeconds()) {
            problems.add("timeoutSeconds may not exceed the image's limit of " + image.timeoutSeconds());
        }
        return problems;
    }

    private static void validate(SandboxImageRequest r, List<String> errors) {
        if (r.imageRef() == null || !ToolSpecValidator.IMAGE_REF.matcher(r.imageRef()).matches()) {
            errors.add("imageRef must be a digest-pinned image reference (name@sha256:<64 hex>)");
        }
        if (r.description() == null || r.description().isBlank()) {
            errors.add("description is required");
        }
        if (r.inputSchema() == null || !"object".equals(r.inputSchema().get("type"))) {
            errors.add("inputSchema must be a schema with type object");
        } else {
            OutputSchema.unsupported(r.inputSchema()).forEach(p -> errors.add("inputSchema: " + p));
        }
        if (r.outputSchema() != null) {
            OutputSchema.unsupported(r.outputSchema()).forEach(p -> errors.add("outputSchema: " + p));
        }
        List<String> hosts = r.egressHosts() == null ? List.of() : r.egressHosts();
        if (hosts.size() > MAX_HOSTS) {
            errors.add("at most " + MAX_HOSTS + " egress hosts");
        }
        for (String host : hosts) {
            if (host == null || !HOST.matcher(host).matches()) {
                errors.add("egress host " + host + " must be a lower-case host name, optionally with :port");
            }
        }
        range(errors, "cpuMillis", r.cpuMillis(), 50, 4000);
        range(errors, "memoryMb", r.memoryMb(), 32, 4096);
        range(errors, "timeoutSeconds", r.timeoutSeconds(), 1, ToolSpecValidator.MAX_SANDBOX_TIMEOUT_SECONDS);
    }

    private static void range(List<String> errors, String field, Integer value, int min, int max) {
        if (value == null || value < min || value > max) {
            errors.add(field + " must be between " + min + " and " + max);
        }
    }

    private ImageRow requireActive(String id) {
        ImageRow row = store.find(id).orElseThrow(() -> new NotFoundException("No sandbox image " + id));
        if (!ACTIVE.equals(row.status())) {
            throw new ConflictException("Sandbox image " + id + " is retired");
        }
        return row;
    }

    private static ImageRow row(String id, SandboxImageRequest r, String user) {
        return new ImageRow(id, r.imageRef(), r.description(), write(r.inputSchema()),
                r.outputSchema() == null ? null : write(r.outputSchema()),
                write(r.egressHosts() == null ? List.of() : r.egressHosts()), r.cpuMillis(), r.memoryMb(), r.timeoutSeconds(),
                ACTIVE, null, user, null, user, null, null);
    }

    static SandboxImageDto toDto(ImageRow r) {
        return new SandboxImageDto(r.id(), r.imageRef(), r.description(), readMap(r.inputSchemaJson()),
                r.outputSchemaJson() == null ? null : readMap(r.outputSchemaJson()), readList(r.egressHostsJson()),
                r.cpuMillis(), r.memoryMb(), r.timeoutSeconds(), r.status(), r.createdAt(), r.createdBy(), r.updatedAt(),
                r.updatedBy(), r.retiredAt(), r.retiredBy());
    }

    private static void requireEnterpriseAdmin(Identity identity) {
        if (!WorkspaceService.ENTERPRISE_ADMIN_ROLE.equals(identity.role())) {
            throw new ForbiddenException("Role " + identity.role() + " is not an enterprise Admin");
        }
    }

    private static void throwIfAny(List<String> errors) {
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not serializable: " + e.getOriginalMessage());
        }
    }

    private static Map<String, Object> readMap(String json) {
        try {
            return JSON.readValue(json, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored schema is not JSON", e);
        }
    }

    private static List<String> readList(String json) {
        try {
            return JSON.readValue(json, LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored host list is not JSON", e);
        }
    }
}
