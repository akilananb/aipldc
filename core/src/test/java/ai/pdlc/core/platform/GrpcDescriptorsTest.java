package ai.pdlc.core.platform;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrpcDescriptorsTest {

    @Test
    void resolvesTheDeclaredMethodAndTheWellKnownTypesItImports() {
        GrpcDescriptors.Resolved m = GrpcDescriptors.resolve(GrpcFixtures.reportSet(), "demo.ReportAgent", "Report");

        assertThat(m.fullMethodName()).isEqualTo("demo.ReportAgent/Report");
        assertThat(m.serverStreaming()).isTrue();
        assertThat(m.clientStreaming()).isFalse();
        assertThat(m.input().findFieldByName("since").getMessageType().getFullName()).isEqualTo("google.protobuf.Timestamp");
    }

    @Test
    void listsEveryMethodWithItsKindAndRequestFields() {
        var methods = GrpcDescriptors.listMethods(GrpcFixtures.reportSet());

        assertThat(methods).extracting(GrpcDescriptors.MethodInfo::method, GrpcDescriptors.MethodInfo::kind,
                        GrpcDescriptors.MethodInfo::callable)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Summarise", "unary", true),
                        org.assertj.core.groups.Tuple.tuple("Report", "server-stream", true),
                        org.assertj.core.groups.Tuple.tuple("Upload", "client-stream", false),
                        org.assertj.core.groups.Tuple.tuple("Chat", "bidi-stream", false));
        assertThat(methods.get(0).requestFields()).extracting(GrpcDescriptors.Field::name, GrpcDescriptors.Field::string)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("input", true), org.assertj.core.groups.Tuple.tuple("prompt", true),
                        org.assertj.core.groups.Tuple.tuple("limit", false), org.assertj.core.groups.Tuple.tuple("since", false));
    }

    @Test
    void refusesWhatCannotBeUsed() {
        assertThatThrownBy(() -> GrpcDescriptors.parse("not base64!")).hasMessage("descriptorSet must be base64");
        assertThatThrownBy(() -> GrpcDescriptors.parse(Base64.getEncoder().encodeToString(new byte[]{(byte) 0xff, 1, 2})))
                .hasMessage("descriptorSet is not a FileDescriptorSet");
        assertThatThrownBy(() -> GrpcDescriptors.parse(Base64.getEncoder().encodeToString(new byte[GrpcDescriptors.MAX_DECODED_BYTES + 1])))
                .hasMessage("descriptorSet is larger than 512 KB");
        FileDescriptorProto missingImport = GrpcFixtures.reportProto().toBuilder().setDependency(0, "acme/common.proto").build();
        assertThatThrownBy(() -> GrpcDescriptors.parse(GrpcFixtures.base64(FileDescriptorSet.newBuilder().addFile(missingImport).build())))
                .hasMessageContaining("descriptorSet is missing acme/common.proto");
        assertThatThrownBy(() -> GrpcDescriptors.resolve(GrpcFixtures.reportSet(), "demo.Other", "Summarise"))
                .hasMessage("descriptorSet has no service demo.Other");
        assertThatThrownBy(() -> GrpcDescriptors.resolve(GrpcFixtures.reportSet(), "demo.ReportAgent", "Delete"))
                .hasMessage("service demo.ReportAgent has no method Delete");
    }
}
