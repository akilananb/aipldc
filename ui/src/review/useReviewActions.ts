import { useMutation, useQueryClient, type UseMutationResult } from '@tanstack/react-query';
import { toast } from 'sonner';
import { api, errorMessage, type AddCommentBody } from '../api';
import { useIdentity } from '../identity';
import { useProjectGates } from '../useProject';
import type { Comment, GateState, ScenarioReview } from '../types';

export interface ReviewActions {
  approve: UseMutationResult<unknown, Error, string>;
  requestChanges: UseMutationResult<unknown, Error, void>;
  retryStep: UseMutationResult<unknown, Error, void>;
  approveAgentResult: UseMutationResult<void, Error, string>;
  addComment: UseMutationResult<Comment, Error, AddCommentBody>;
  reviewScenario: UseMutationResult<ScenarioReview, Error, { version: number; scenario: string; status: 'meets' | 'not-reviewed' }>;
  prApprove: UseMutationResult<unknown, Error, string>;
  prRequestChanges: UseMutationResult<unknown, Error, void>;
  planApprove: UseMutationResult<unknown, Error, string>;
  planRequestChanges: UseMutationResult<unknown, Error, void>;
  releaseRequestChanges: UseMutationResult<unknown, Error, void>;
  hasOpenBlocking: boolean;
  g1RoleAllowed: boolean;
  g2RoleAllowed: boolean;
  planRoleAllowed: boolean;
  alreadyApproved: boolean;
  approveDisabled: boolean;
  disabledReason: string | null;
}

export function useReviewActions(
  storyId: string | null,
  comments: Comment[],
  gate: GateState | null,
  profile: string | null | undefined,
  onApproveSuccess?: () => void,
): ReviewActions {
  const identity = useIdentity();
  const queryClient = useQueryClient();
  const { roles: gateRoles, loading: gatesLoading } = useProjectGates(profile);

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

  const retryStep = useMutation({
    mutationFn: () => api.retryStep(storyId!),
    onSuccess: () => {
      invalidateStory();
      toast.success('Retrying');
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

  const reviewScenario = useMutation({
    mutationFn: ({ version, scenario, status }: { version: number; scenario: string; status: 'meets' | 'not-reviewed' }) =>
      api.reviewScenario(storyId!, version, scenario, status),
    onSuccess: (_, { status }) => {
      void queryClient.invalidateQueries({ queryKey: ['artifact', storyId] });
      toast.success(status === 'meets' ? 'Scenario marked as meeting criteria' : 'Scenario review cleared');
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

  const planApprove = useMutation({
    mutationFn: (note: string) => api.planApprove(storyId!, note),
    onSuccess: () => {
      invalidateStory();
      toast.success('Plan approved');
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  const planRequestChanges = useMutation({
    mutationFn: () => api.planRequestChanges(storyId!),
    onSuccess: () => {
      invalidateStory();
      toast.success('Changes requested');
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  const releaseRequestChanges = useMutation({
    mutationFn: () => api.releaseRequestChanges(storyId!),
    onSuccess: () => {
      invalidateStory();
      void queryClient.invalidateQueries({ queryKey: ['release', storyId] });
      toast.success('Changes requested');
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  const hasOpenBlocking = comments.some((c) => c.blocking && c.resolvedInVersion == null);

  const currentApprovals = gate?.approvals ?? {};
  const g1RoleAllowed = (gateRoles.G1 ?? []).includes(identity.role);
  const g2RoleAllowed = (gateRoles.G2 ?? []).includes(identity.role);
  const planRoleAllowed = (gateRoles.PLAN ?? []).includes(identity.role);
  const alreadyApproved = Object.values(currentApprovals).some((a) => a.who === identity.user);

  const approveDisabled = !storyId || hasOpenBlocking || !g1RoleAllowed || alreadyApproved;

  const disabledReason: string | null = gatesLoading
    ? 'Loading project gates'
    : hasOpenBlocking
      ? 'Resolve the blocking comment first'
      : !g1RoleAllowed
        ? `Role ${identity.role} is not a gate 1 checker`
        : alreadyApproved
          ? 'You already approved this version'
          : null;

  return {
    approve,
    requestChanges,
    retryStep,
    approveAgentResult,
    addComment,
    reviewScenario,
    prApprove,
    prRequestChanges,
    planApprove,
    planRequestChanges,
    releaseRequestChanges,
    hasOpenBlocking,
    g1RoleAllowed,
    g2RoleAllowed,
    planRoleAllowed,
    alreadyApproved,
    approveDisabled,
    disabledReason,
  };
}
