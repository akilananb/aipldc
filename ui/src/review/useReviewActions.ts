import { useMutation, useQueryClient, type UseMutationResult } from '@tanstack/react-query';
import { toast } from 'sonner';
import { api, errorMessage, type AddCommentBody } from '../api';
import { useIdentity } from '../identity';
import { GATE_ROLES } from '../gates';
import type { Comment, GateState } from '../types';

export interface ReviewActions {
  approve: UseMutationResult<unknown, Error, string>;
  requestChanges: UseMutationResult<unknown, Error, void>;
  approveAgentResult: UseMutationResult<void, Error, string>;
  addComment: UseMutationResult<Comment, Error, AddCommentBody>;
  prApprove: UseMutationResult<unknown, Error, string>;
  prRequestChanges: UseMutationResult<unknown, Error, void>;
  hasOpenBlocking: boolean;
  g1RoleAllowed: boolean;
  g2RoleAllowed: boolean;
  alreadyApproved: boolean;
  approveDisabled: boolean;
  disabledReason: string | null;
}

export function useReviewActions(
  storyId: string | null,
  comments: Comment[],
  gate: GateState | null,
  onApproveSuccess?: () => void,
): ReviewActions {
  const identity = useIdentity();
  const queryClient = useQueryClient();

  function invalidateStory() {
    void queryClient.invalidateQueries({ queryKey: ['item', storyId] });
    void queryClient.invalidateQueries({ queryKey: ['artifact', storyId] });
  }

  const approve = useMutation({
    mutationFn: (note: string) => api.approve(storyId!, note),
    onSuccess: () => {
      invalidateStory();
      onApproveSuccess?.();
      toast.success('Approved');
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  const requestChanges = useMutation({
    mutationFn: () => api.requestChanges(storyId!),
    onSuccess: () => {
      invalidateStory();
      toast.success('Changes requested');
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  const approveAgentResult = useMutation({
    mutationFn: (commentId: string) => api.approveAgentResult(storyId!, commentId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['artifact', storyId] });
      toast.success('Agent result approved');
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  const addComment = useMutation({
    mutationFn: (body: AddCommentBody) => api.addComment(storyId!, body),
    onSuccess: () => {
      invalidateStory();
      toast.success('Comment posted');
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  const prApprove = useMutation({
    mutationFn: (note: string) => api.prApprove(storyId!, note),
    onSuccess: () => {
      invalidateStory();
      toast.success('PR approved');
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  const prRequestChanges = useMutation({
    mutationFn: () => api.prRequestChanges(storyId!),
    onSuccess: () => {
      invalidateStory();
      toast.success('Changes requested');
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  const hasOpenBlocking = comments.some((c) => c.blocking && c.resolvedInVersion == null);

  const currentApprovals = gate?.approvals ?? {};
  const g1RoleAllowed = GATE_ROLES.G1.includes(identity.role);
  const g2RoleAllowed = GATE_ROLES.G2.includes(identity.role);
  const alreadyApproved = Object.values(currentApprovals).some((a) => a.who === identity.user);

  const approveDisabled = !storyId || hasOpenBlocking || !g1RoleAllowed || alreadyApproved;

  const disabledReason: string | null = hasOpenBlocking
    ? 'Resolve the blocking comment first'
    : !g1RoleAllowed
      ? `Role ${identity.role} is not a gate 1 checker`
      : alreadyApproved
        ? 'You already approved this version'
        : null;

  return {
    approve,
    requestChanges,
    approveAgentResult,
    addComment,
    prApprove,
    prRequestChanges,
    hasOpenBlocking,
    g1RoleAllowed,
    g2RoleAllowed,
    alreadyApproved,
    approveDisabled,
    disabledReason,
  };
}
