package ai.pdlc.controlplane.web.dto;

import ai.pdlc.core.domain.Approval;
import ai.pdlc.core.workflow.ReviewState;
import ai.pdlc.core.workflow.StepFailure;

import java.util.Map;
import java.util.stream.Collectors;

public record ReviewStateDto(int version, Map<String, ApprovalDto> approvals, int openBlockingComments, String stage,
                              StepFailureDto lastFailure) {

    public static ReviewStateDto from(ReviewState state) {
        Map<String, ApprovalDto> approvals = state.approvals().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> ApprovalDto.from(e.getValue())));
        return new ReviewStateDto(state.version(), approvals, state.openComments().size(), state.stage().wireValue(),
                StepFailureDto.from(state.lastFailure()));
    }

    public record ApprovalDto(String who, String role, int version, String contentHash, String at) {
        public static ApprovalDto from(Approval a) {
            return new ApprovalDto(a.who(), a.role(), a.version(), a.contentHash(), a.at().toString());
        }
    }

    /** A currently-blocked reasoning/LLM step (see {@code FeatureWorkflowImpl#reasoningStep}) —
     * null when nothing is blocked. */
    public record StepFailureDto(String step, String message, long atEpochMilli) {
        public static StepFailureDto from(StepFailure f) {
            return f == null ? null : new StepFailureDto(f.step(), f.message(), f.atEpochMilli());
        }
    }
}
