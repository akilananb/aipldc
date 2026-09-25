package ai.pdlc.core.platform;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;

import java.util.Base64;

/** Descriptor sets built in code (no protoc): what {@code protoc --include_imports --descriptor_set_out} would write. */
final class GrpcFixtures {

    private GrpcFixtures() {
    }

    static FileDescriptorProto reportProto() {
        return FileDescriptorProto.newBuilder()
                .setName("demo/report.proto").setPackage("demo").setSyntax("proto3")
                .addDependency("google/protobuf/timestamp.proto")
                .addMessageType(DescriptorProto.newBuilder().setName("SummariseRequest")
                        .addField(field("input", 1, FieldDescriptorProto.Type.TYPE_STRING))
                        .addField(field("prompt", 2, FieldDescriptorProto.Type.TYPE_STRING))
                        .addField(field("limit", 3, FieldDescriptorProto.Type.TYPE_INT32))
                        .addField(field("since", 4, FieldDescriptorProto.Type.TYPE_MESSAGE).setTypeName(".google.protobuf.Timestamp")))
                .addMessageType(DescriptorProto.newBuilder().setName("SummariseReply")
                        .addField(field("summary", 1, FieldDescriptorProto.Type.TYPE_STRING)))
                .addService(ServiceDescriptorProto.newBuilder().setName("ReportAgent")
                        .addMethod(method("Summarise", false, false))
                        .addMethod(method("Report", false, true))
                        .addMethod(method("Upload", true, false))
                        .addMethod(method("Chat", true, true)))
                .build();
    }

    static String reportSet() {
        return base64(FileDescriptorSet.newBuilder().addFile(reportProto()).build());
    }

    static String base64(FileDescriptorSet set) {
        return Base64.getEncoder().encodeToString(set.toByteArray());
    }

    private static FieldDescriptorProto.Builder field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL).setJsonName(name);
    }

    private static MethodDescriptorProto method(String name, boolean clientStreaming, boolean serverStreaming) {
        return MethodDescriptorProto.newBuilder().setName(name)
                .setInputType(".demo.SummariseRequest").setOutputType(".demo.SummariseReply")
                .setClientStreaming(clientStreaming).setServerStreaming(serverStreaming).build();
    }
}
