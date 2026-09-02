import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Badge, Box, Flex, Tabs, Text } from '@radix-ui/themes';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';
import { api, errorMessage } from '../api';
import type { CommentIntent, DocApproval, ItemDetail } from '../types';
import { extendLineTarget, formatLineTarget, parseLineTarget } from '../ui-utils';
import PageHeader from '../components/PageHeader';
import StatusBadge from '../components/StatusBadge';
import ErrorCallout from '../components/ErrorCallout';
import Panel from '../components/Panel';
import { useReviewActions } from '../review/useReviewActions';
import GatePanel from '../review/GatePanel';
import CommentPanel from '../review/CommentPanel';
import PreviewTab from '../review/PreviewTab';
import ReviewMdTab from '../review/ReviewMdTab';
import QualityTab from '../review/QualityTab';

interface Props {
  item: ItemDetail;
}

function ApprovalBanner({ approvals }: { approvals: DocApproval[] }) {
  const hasSquadLead = approvals.some((a) => a.role === 'SquadLead');
  if (!hasSquadLead) {
    return <Badge color="amber">awaiting gate-1 approval</Badge>;
  }

  // Highest-version approval per role, SquadLead first.
  const latestByRole = new Map<string, DocApproval>();
  for (const a of approvals) {
    const current = latestByRole.get(a.role);
    if (!current || a.version > current.version) {
      latestByRole.set(a.role, a);
    }
  }
  const ordered = [...latestByRole.values()].sort((a, b) => {
    if (a.role === 'SquadLead') return -1;
    if (b.role === 'SquadLead') return 1;
    return a.role.localeCompare(b.role);
  });

  return (
    <Flex gap="2" wrap="wrap">
      {ordered.map((a) => (
        <Badge key={a.role} color="green">
          approved by {a.who} ({a.role}) · v{a.version} · {new Date(a.at).toLocaleString()}
        </Badge>
      ))}
    </Flex>
  );
}

export default function TaskDetailPage({ item }: Props) {
  const [draftTarget, setDraftTarget] = useState<string | null>(null);

  const specDocsQuery = useQuery({
    queryKey: ['spec-docs', item.id],
    queryFn: () => api.getSpecDocs(item.id),
    refetchInterval: 5000,
  });

  const docs = specDocsQuery.data;
  const storyId = docs?.storyId ?? null;

  // The task's own work_items row has no artifact of its own (it never accrues comments/gate
  // state) - comments and G1 sign-off live on the parent story's artifact, exactly like the story
  // review page, so every mutation here targets storyId, not item.id.
  const storyItemQuery = useQuery({
    queryKey: ['item', storyId],
    queryFn: () => api.getItem(storyId!),
    enabled: !!storyId,
    refetchInterval: 2000,
  });
  const story = storyItemQuery.data;
  const latestVersion = story?.latestVersion ?? 0;

  const storyArtifactQuery = useQuery({
    queryKey: ['artifact', storyId, latestVersion],
    queryFn: () => api.getArtifact(storyId!, latestVersion),
    enabled: !!storyId && latestVersion > 0,
    refetchInterval: 2000,
  });

  const currentComments = storyArtifactQuery.data?.comments ?? [];
  const queryClient = useQueryClient();
  const actions = useReviewActions(storyId, currentComments, story?.gate ?? null, () =>
    queryClient.invalidateQueries({ queryKey: ['spec-docs', item.id] }),
  );

  const docsLoading = specDocsQuery.isLoading;

  return (
    <Box>
      <PageHeader
        backTo={docs ? { to: `/items/${docs.storyId}`, label: `Story: ${docs.storyTitle}` } : { to: '/', label: 'Items' }}
        title={item.title}
        badges={
          <>
            <Badge color="gray">task</Badge>
            <StatusBadge state={item.canonicalState} />
          </>
        }
        meta={`board ${item.boardId} · profile ${item.profile}`}
      />

      <Box mb="4">
        <Panel>{docs ? <ApprovalBanner approvals={docs.approvals} /> : <Badge color="amber">awaiting gate-1 approval</Badge>}</Panel>
      </Box>

      <Box mb="4">
        <Panel title="Context">
          <Box className="review-md">
            <Markdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeHighlight]}>{item.description}</Markdown>
          </Box>
        </Panel>
      </Box>

      {storyId && story && (
        <Box mb="4">
          <GatePanel item={story} actions={actions} />
        </Box>
      )}

      <Tabs.Root defaultValue="story">
        <Tabs.List>
          <Tabs.Trigger value="story">Story & comments</Tabs.Trigger>
          <Tabs.Trigger value="spec">Design spec</Tabs.Trigger>
          <Tabs.Trigger value="tasks">Tasks plan</Tabs.Trigger>
          <Tabs.Trigger value="quality">Quality</Tabs.Trigger>
        </Tabs.List>

        <Box pt="4">
          <Tabs.Content value="story">
            {(docsLoading || storyItemQuery.isLoading || storyArtifactQuery.isLoading) && (
              <Text color="gray">Loading documents…</Text>
            )}
            {specDocsQuery.isError && <ErrorCallout title="Failed to load documents" error={specDocsQuery.error} />}
            {storyArtifactQuery.isError && (
              <ErrorCallout title="Failed to load artifact" error={storyArtifactQuery.error} />
            )}
            {storyArtifactQuery.data && (
              <div className="review-layout">
                <Box style={{ minWidth: 0 }}>
                  <PreviewTab
                    markdown={storyArtifactQuery.data.storyMarkdown}
                    onLineSelect={(line, shift) => setDraftTarget((prev) => extendLineTarget(prev, line, shift))}
                    onRangeSelect={(start, end) => setDraftTarget(formatLineTarget(start, end))}
                    selectedRange={parseLineTarget(draftTarget)}
                  />
                </Box>
                <div className="review-sidebar">
                  <CommentPanel
                    comments={currentComments}
                    draftTarget={draftTarget}
                    onDraftTarget={setDraftTarget}
                    onAddComment={(body: { target: string; text: string; intent: CommentIntent; blocking: boolean }) =>
                      actions.addComment.mutate(body)
                    }
                    submitting={actions.addComment.isPending}
                    onApproveAgentResult={(commentId) => actions.approveAgentResult.mutate(commentId)}
                    approvingAgentResult={actions.approveAgentResult.isPending}
                    canApproveAgentResult={actions.g1RoleAllowed}
                  />
                </div>
              </div>
            )}
          </Tabs.Content>
          <Tabs.Content value="spec">
            <ReviewMdTab
              loading={docsLoading}
              error={specDocsQuery.isError ? errorMessage(specDocsQuery.error) : null}
              content={docs?.specMd ?? null}
            />
          </Tabs.Content>
          <Tabs.Content value="tasks">
            <ReviewMdTab
              loading={docsLoading}
              error={specDocsQuery.isError ? errorMessage(specDocsQuery.error) : null}
              content={docs?.tasksMd ?? null}
            />
          </Tabs.Content>
          <Tabs.Content value="quality">
            <QualityTab id={item.id} />
          </Tabs.Content>
        </Box>
      </Tabs.Root>
    </Box>
  );
}
