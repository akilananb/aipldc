package ai.pdlc.controlplane.web.dto;

import ai.pdlc.core.domain.Approval;
import ai.pdlc.core.workflow.ReviewState;

import java.util.Map;
import java.util.stream.Collectors;

public record ReviewStateDto(int version, Map<String, ApprovalDto> approvals, int openBlockingComments, String stage) {

    public static ReviewStateDto from(ReviewState state) {
        Map<String, ApprovalDto> approvals = state.approvals().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> ApprovalDto.from(e.getValue())));
        return new ReviewStateDto(state.version(), approvals, state.openComments().size(), state.stage().wireValue());
    }

    public record ApprovalDto(String who, String role, int version, String contentHash, String at) {
        public static ApprovalDto from(Approval a) {
            return new ApprovalDto(a.who(), a.role(), a.version(), a.contentHash(), a.at().toString());
        }
    }
}
