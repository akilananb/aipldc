package ai.pdlc.controlplane.web;

import ai.pdlc.controlplane.identity.IdentityResolver;
import ai.pdlc.controlplane.platform.Capability;
import ai.pdlc.controlplane.platform.WorkspaceService;
import ai.pdlc.core.platform.GrpcDescriptors;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Lists the methods in a gRPC descriptor set an author is registering (slice 2.6b). Pure: it parses
 * what the author uploaded and calls no service - the methods a grpc agent may call are only ever
 * the ones its published version registered, never ones discovered by reflection.
 */
@RestController
public class GrpcDescriptorsController {

    /** A base64 {@code FileDescriptorSet}, as {@code protoc --include_imports --descriptor_set_out} writes it. */
    public record DescribeRequest(String descriptorSet) {
    }

    private final WorkspaceService workspaces;
    private final IdentityResolver identityResolver;

    public GrpcDescriptorsController(WorkspaceService workspaces, IdentityResolver identityResolver) {
        this.workspaces = workspaces;
        this.identityResolver = identityResolver;
    }

    @PostMapping("/api/workspaces/{workspaceId}/grpc/describe")
    public List<GrpcDescriptors.MethodInfo> describe(@PathVariable String workspaceId, @RequestBody DescribeRequest request,
                                                     HttpServletRequest http) {
        workspaces.require(workspaceId, identityResolver.resolve(http), Capability.AUTHOR, Capability.WORKSPACE_ADMIN);
        try {
            return GrpcDescriptors.listMethods(request == null ? null : request.descriptorSet());
        } catch (GrpcDescriptors.InvalidDescriptorsException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }
}
