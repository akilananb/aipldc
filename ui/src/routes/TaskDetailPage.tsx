import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Box, Tabs, Text } from '@radix-ui/themes';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';
import { api, errorMessage } from '../api';
import type { CommentIntent, DocApproval, ItemDetail } from '../types';
import { extendLineTarget, formatLineTarget, parseLineTarget, parseStoryDoc } from '../ui-utils';
import PageHeader, { MetaItems } from '../components/PageHeader';
import ProjectMetaLink from '../components/ProjectMetaLink';
import StatusBadge from '../components/StatusBadge';
import AgentActivityBadge from '../components/AgentActivityBadge';
import DemoSnapshotBadge from '../components/DemoSnapshotBadge';
import ErrorCallout from '../components/ErrorCallout';
import Surface from '../components/Surface';
import ViewHead from '../components/ViewHead';
import { useReviewActions } from '../review/useReviewActions';
import ReviewBar from '../review/ReviewBar';
import CommentPanel from '../review/CommentPanel';
import PreviewTab from '../review/PreviewTab';
import ReviewMdTab from '../review/ReviewMdTab';
import QualityTab from '../review/QualityTab';
import ActivityTab from '../review/ActivityTab';

interface Props {
  item: ItemDetail;
}

function ApprovalPills({ approvals }: { approvals: DocApproval[] }) {
  const hasSquadLead = approvals.some((a) => a.role === 'SquadLead');
  if (!hasSquadLead) {
    return <span className="pill review">awaiting gate-1 approval</span>;
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
    <>
      {ordered.map((a) => (
        <span key={a.role} className="pill pass">
          approved by {a.who} ({a.role}) · v{a.version}
        </span>
      ))}
    </>
  );
}

export default function TaskDetailPage({ item }: Props) {
  const [draftTarget, setDraftTarget] = useState<string | null>(null);
  const [tab, setTab] = useState('brief');

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
  const actions = useReviewActions(storyId, currentComments, story?.gate ?? null, story?.profile, () =>
    queryClient.invalidateQueries({ queryKey: ['spec-docs', item.id] }),
  );

  const docsLoading = specDocsQuery.isLoading;

  const readOnly = item.snapshot != null || (story != null && story.snapshot != null);

  const storyDoc = useMemo(
    () => parseStoryDoc(storyArtifactQuery.data?.storyMarkdown ?? ''),
    [storyArtifactQuery.data?.storyMarkdown],
  );
  const commentBlocks = useMemo(() => {
    const result: { label: string; target: string }[] = [];
    if (storyDoc.story) result.push({ label: 'User story', target: formatLineTarget(storyDoc.story.start, storyDoc.story.end) });
    if (storyDoc.contextStart != null && storyDoc.contextEnd != null) {
      result.push({ label: 'Context & constraints', target: formatLineTarget(storyDoc.contextStart, storyDoc.contextEnd) });
    }
    storyDoc.scenarios.forEach((s, i) => {
      result.push({ label: `Scenario ${i + 1}: ${s.name}`, target: formatLineTarget(s.start, s.end) });
    });
    return result;
  }, [storyDoc]);

  return (
    <Box>
      <PageHeader
        title={item.title}
        badges={
          <>
            <span className="pill">TASK</span>
            <StatusBadge state={item.canonicalState} />
            <AgentActivityBadge run={item.activeRun} />
            {item.snapshot && <DemoSnapshotBadge snapshot={item.snapshot} />}
            {docs ? <ApprovalPills approvals={docs.approvals} /> : <span className="pill review">awaiting gate-1 approval</span>}
          </>
        }
        meta={<MetaItems items={[`board ${item.boardId}`, <ProjectMetaLink profile={item.profile} />]} />}
        subtitle={
          docs ? (
            <>
              Task of story: <Link to={`/items/${docs.storyId}`}>{docs.storyTitle}</Link>
            </>
          ) : undefined
        }
      />

      {storyId && story && <ReviewBar item={story} actions={actions} />}

      <Surface
        value={tab}
        onValueChange={setTab}
        tabs={[
          { value: 'brief', label: 'Brief' },
          { value: 'story', label: 'Parent story' },
          { value: 'spec', label: 'Design spec' },
          { value: 'tasks', label: 'Tasks plan' },
          { value: 'quality', label: 'Checks' },
          { value: 'activity', label: 'Activity' },
        ]}
        inspector={
          tab === 'story' ? (
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
              readOnly={readOnly}
              blocks={commentBlocks}
            />
          ) : undefined
        }
      >
        <Tabs.Content value="brief">
          <ViewHead title="Task brief" />
          <Box className="review-md">
            <Markdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeHighlight]}>{item.description}</Markdown>
          </Box>
        </Tabs.Content>

        <Tabs.Content value="story">
          {(docsLoading || storyItemQuery.isLoading || storyArtifactQuery.isLoading) && (
            <Text color="gray">Loading documents…</Text>
          )}
          {specDocsQuery.isError && <ErrorCallout title="Failed to load documents" error={specDocsQuery.error} />}
          {storyArtifactQuery.isError && (
            <ErrorCallout title="Failed to load artifact" error={storyArtifactQuery.error} />
          )}
          {storyArtifactQuery.data && (
            <PreviewTab
              markdown={storyArtifactQuery.data.storyMarkdown}
              reviews={storyArtifactQuery.data.scenarioReviews}
              canReview={actions.g1RoleAllowed && !readOnly}
              reviewing={actions.reviewScenario.isPending ? (actions.reviewScenario.variables?.scenario ?? null) : null}
              onReviewScenario={(scenario, status) =>
                actions.reviewScenario.mutate({ version: latestVersion, scenario, status })
              }
              onLineSelect={(line, shift) => setDraftTarget((prev) => extendLineTarget(prev, line, shift))}
              onRangeSelect={(start, end) => setDraftTarget(formatLineTarget(start, end))}
              selectedRange={parseLineTarget(draftTarget)}
            />
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
          <QualityTab id={item.id} label="Checks" />
        </Tabs.Content>
        <Tabs.Content value="activity">
          <ActivityTab id={item.id} snapshot={readOnly} />
        </Tabs.Content>
      </Surface>
    </Box>
  );
}
