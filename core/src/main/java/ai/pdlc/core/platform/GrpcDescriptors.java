package ai.pdlc.core.platform;

import com.google.protobuf.AnyProto;
import com.google.protobuf.ApiProto;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DurationProto;
import com.google.protobuf.EmptyProto;
import com.google.protobuf.FieldMaskProto;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.SourceContextProto;
import com.google.protobuf.StructProto;
import com.google.protobuf.TimestampProto;
import com.google.protobuf.TypeProto;
import com.google.protobuf.WrappersProto;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The registered descriptors of a {@code grpc} agent (Phase 2 slice 2.6b). A gRPC agent version
 * carries a base64 {@code FileDescriptorSet} (what {@code protoc --include_imports
 * --descriptor_set_out} writes); this class turns it into descriptors. Pure: no network, and never
 * server reflection - the only methods that exist are the ones the published version registered.
 *
 * <p>The protobuf well-known types ({@code google/protobuf/*.proto}) resolve from the built-in
 * descriptors when the set omits them.
 */
public final class GrpcDescriptors {

    /** Decoded size cap: descriptors are part of every published version and its hash. */
    public static final int MAX_DECODED_BYTES = 512 * 1024;

    private static final Map<String, Descriptors.FileDescriptor> WELL_KNOWN = new HashMap<>();

    static {
        for (Descriptors.FileDescriptor fd : List.of(AnyProto.getDescriptor(), ApiProto.getDescriptor(),
                DurationProto.getDescriptor(), EmptyProto.getDescriptor(), FieldMaskProto.getDescriptor(),
                SourceContextProto.getDescriptor(), StructProto.getDescriptor(), TimestampProto.getDescriptor(),
                TypeProto.getDescriptor(), WrappersProto.getDescriptor())) {
            WELL_KNOWN.put(fd.getName(), fd);
        }
    }

    private GrpcDescriptors() {
    }

    /** A descriptor set that cannot be used; the message is safe to show an author. */
    public static final class InvalidDescriptorsException extends RuntimeException {
        public InvalidDescriptorsException(String message) {
            super(message);
        }
    }

    /** One callable method with its message types. */
    public record Resolved(String service, String method, Descriptors.Descriptor input, Descriptors.Descriptor output,
                           boolean clientStreaming, boolean serverStreaming) {

        /** The gRPC wire name, {@code package.Service/Method}. */
        public String fullMethodName() {
            return service + "/" + method;
        }

        public boolean callable() {
            return !clientStreaming;
        }
    }

    /** A request field, as the Studio shows it. */
    public record Field(String name, String type, boolean repeated, boolean string) {
    }

    /** One method as listed for an author choosing what the agent calls. */
    public record MethodInfo(String service, String method, String kind, boolean callable, String requestType,
                             List<Field> requestFields, String responseType) {
    }

    /** Parses and links the whole set; every file's dependencies must be in it or be well-known types. */
    public static List<Descriptors.FileDescriptor> parse(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new InvalidDescriptorsException("descriptorSet is required");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new InvalidDescriptorsException("descriptorSet must be base64");
        }
        if (bytes.length > MAX_DECODED_BYTES) {
            throw new InvalidDescriptorsException("descriptorSet is larger than " + MAX_DECODED_BYTES / 1024 + " KB");
        }
        DescriptorProtos.FileDescriptorSet set;
        try {
            set = DescriptorProtos.FileDescriptorSet.parseFrom(bytes);
        } catch (InvalidProtocolBufferException e) {
            throw new InvalidDescriptorsException("descriptorSet is not a FileDescriptorSet");
        }
        if (set.getFileCount() == 0) {
            throw new InvalidDescriptorsException("descriptorSet has no files");
        }
        Map<String, DescriptorProtos.FileDescriptorProto> protos = new LinkedHashMap<>();
        for (DescriptorProtos.FileDescriptorProto p : set.getFileList()) {
            if (protos.put(p.getName(), p) != null) {
                throw new InvalidDescriptorsException("descriptorSet lists " + p.getName() + " twice");
            }
        }
        Map<String, Descriptors.FileDescriptor> built = new HashMap<>();
        List<Descriptors.FileDescriptor> files = new ArrayList<>();
        for (String name : protos.keySet()) {
            files.add(build(name, protos, built, new HashSet<>()));
        }
        return files;
    }

    private static Descriptors.FileDescriptor build(String name, Map<String, DescriptorProtos.FileDescriptorProto> protos,
                                                    Map<String, Descriptors.FileDescriptor> built, Set<String> visiting) {
        Descriptors.FileDescriptor done = built.get(name);
        if (done != null) {
            return done;
        }
        DescriptorProtos.FileDescriptorProto proto = protos.get(name);
        if (proto == null) {
            Descriptors.FileDescriptor wellKnown = WELL_KNOWN.get(name);
            if (wellKnown != null) {
                return wellKnown;
            }
            throw new InvalidDescriptorsException("descriptorSet is missing " + name
                    + " (generate it with protoc --include_imports)");
        }
        if (!visiting.add(name)) {
            throw new InvalidDescriptorsException("descriptorSet has an import cycle at " + name);
        }
        List<Descriptors.FileDescriptor> deps = new ArrayList<>();
        for (String dep : proto.getDependencyList()) {
            deps.add(build(dep, protos, built, visiting));
        }
        try {
            Descriptors.FileDescriptor fd = Descriptors.FileDescriptor.buildFrom(proto, deps.toArray(Descriptors.FileDescriptor[]::new));
            built.put(name, fd);
            return fd;
        } catch (Descriptors.DescriptorValidationException e) {
            throw new InvalidDescriptorsException("descriptorSet file " + name + " is invalid: " + e.getDescription());
        }
    }

    /** The declared method, or an exception naming what is missing. */
    public static Resolved resolve(String base64, String service, String method) {
        for (Descriptors.FileDescriptor fd : parse(base64)) {
            for (Descriptors.ServiceDescriptor s : fd.getServices()) {
                if (s.getFullName().equals(service)) {
                    Descriptors.MethodDescriptor m = s.findMethodByName(method == null ? "" : method);
                    if (m == null) {
                        throw new InvalidDescriptorsException("service " + service + " has no method " + method);
                    }
                    return new Resolved(s.getFullName(), m.getName(), m.getInputType(), m.getOutputType(),
                            m.isClientStreaming(), m.isServerStreaming());
                }
            }
        }
        throw new InvalidDescriptorsException("descriptorSet has no service " + service);
    }

    /** Every method of every service in the set, for the Studio's method picker. */
    public static List<MethodInfo> listMethods(String base64) {
        List<MethodInfo> methods = new ArrayList<>();
        for (Descriptors.FileDescriptor fd : parse(base64)) {
            for (Descriptors.ServiceDescriptor s : fd.getServices()) {
                for (Descriptors.MethodDescriptor m : s.getMethods()) {
                    List<Field> fields = new ArrayList<>();
                    for (Descriptors.FieldDescriptor f : m.getInputType().getFields()) {
                        fields.add(new Field(f.getName(), typeName(f), f.isRepeated(), isString(f)));
                    }
                    methods.add(new MethodInfo(s.getFullName(), m.getName(), kind(m.isClientStreaming(), m.isServerStreaming()),
                            !m.isClientStreaming(), m.getInputType().getFullName(), fields, m.getOutputType().getFullName()));
                }
            }
        }
        return methods;
    }

    public static String kind(boolean clientStreaming, boolean serverStreaming) {
        if (clientStreaming) {
            return serverStreaming ? "bidi-stream" : "client-stream";
        }
        return serverStreaming ? "server-stream" : "unary";
    }

    /** A singular {@code string} field - the only kind that can carry the rendered prompt. */
    public static boolean isString(Descriptors.FieldDescriptor f) {
        return f.getType() == Descriptors.FieldDescriptor.Type.STRING && !f.isRepeated();
    }

    private static String typeName(Descriptors.FieldDescriptor f) {
        return switch (f.getJavaType()) {
            case MESSAGE -> f.getMessageType().getFullName();
            case ENUM -> f.getEnumType().getFullName();
            default -> f.getType().name().toLowerCase();
        };
    }
}
