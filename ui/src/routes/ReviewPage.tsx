import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, Check, GitBranch, Sparkles } from 'lucide-react';
import { Box, Button, Callout, Select, Skeleton, Spinner, Text, Tabs } from '@radix-ui/themes';
import { api } from '../api';
import type { CommentIntent } from '../types';
import { agentRunLabel, extendLineTarget, formatLineTarget, isChildOf, parseLineTarget, parseQualityFindings, parseStoryDoc } from '../ui-utils';
import RelativeTime from '../components/RelativeTime';
import { requestCompose } from '../composer';
import { useProjectGates } from '../useProject';
import PageHeader, { MetaItems } from '../components/PageHeader';
import ProjectMetaLink from '../components/ProjectMetaLink';
import StatusBadge from '../components/StatusBadge';
import AgentActivityBadge from '../components/AgentActivityBadge';
import DemoSnapshotBadge from '../components/DemoSnapshotBadge';
import ErrorCallout from '../components/ErrorCallout';
import EmptyState from '../components/EmptyState';
import CopyHash from '../components/CopyHash';
import Surface from '../components/Surface';
import ViewHead from '../components/ViewHead';
import ClarificationTab from '../review/ClarificationTab';
import ClarificationRail from '../review/ClarificationRail';
import { useReviewActions } from '../review/useReviewActions';
import DeliveryMap from '../review/DeliveryMap';
import ReviewBar from '../review/ReviewBar';
import CommentPanel from '../review/CommentPanel';
import PreviewTab from '../review/PreviewTab';
import SourceTab from '../review/SourceTab';
import DiffTab from '../review/DiffTab';
import ReviewMdTab from '../review/ReviewMdTab';
import QualityTab from '../review/QualityTab';
import ActivityTab from '../review/ActivityTab';
import ReleaseTab from '../review/ReleaseTab';
import { useTaskGraph } from '../review/useTaskGraph';
import { useClarification } from '../review/useClarification';
import ExecutionGraph from '../review/ExecutionGraph';
import TaskPane from '../review/TaskPane';
import BuildEvidence from '../review/BuildEvidence';
import { currentRank, phaseOf, PHASE_LABEL, stageSubtitle, type Phase } from '../review/pipeline';
import TaskDetailPage from './TaskDetailPage';
import FeaturePage from './FeaturePage';
import BoardItemPage from './BoardItemPage';

type Tab = 'clarify' | 'preview' | 'source' | 'diff' | 'tasks' | 'quality' | 'release' | 'activity' | 'reviewmd';

const VALID_TABS: Tab[] = ['clarify', 'preview', 'source', 'diff', 'tasks', 'quality', 'release', 'activity', 'reviewmd'];
const PHASE_ORDER: Phase[] = ['clarify', 'specify', 'plan', 'build', 'release'];

function nextPhaseLabel(rank: number): string {
  if (rank < 0) return '—';
  const idx = PHASE_ORDER.indexOf(phaseOf(rank));
  const next = PHASE_ORDER[idx + 1];
  return next ? PHASE_LABEL[next] : '—';
}

export default function ReviewPage() {
  const { id } = useParams<{ id: string }>();

  const [selectedVersion, setSelectedVersion] = useState<number | null>(null);
  const [params, setParams] = useSearchParams();
  const rawTab = params.get('tab');
  const tab = (VALID_TABS.includes(rawTab as Tab) ? rawTab : 'preview') as Tab;
  const setTab = (t: Tab) =>
    setParams(
      (p) => {
        p.set('tab', t);
        return p;
      },
      { replace: true },
    );
  const selectedTaskId = params.get('task');
  const setSelectedTask = (taskId: string | null) =>
    setParams(
      (p) => {
        if (taskId) p.set('task', taskId);
        else p.delete('task');
        return p;
      },
      { replace: true },
    );
  const [draftTarget, setDraftTarget] = useState<string | null>(null);

  const itemQuery = useQuery({
    queryKey: ['item', id],
    queryFn: () => api.getItem(id!),
    enabled: !!id,
    refetchInterval: 2000,
  });

  const item = itemQuery.data;
  const latestVersion = item?.latestVersion ?? 0;

  useEffect(() => {
    if (!item) return;
    document.title = `${item.boardId} · eLoop.ai`;
    return () => {
      document.title = 'eLoop.ai';
    };
  }, [item?.boardId]);

  // Tasks are their own work_items rows (kind "task", parentId = this story's boardId, see
  // BoardSideEffectsImpl#publishTasks) - drilled into here instead of cluttering the top-level
  // list (ItemListPage filters kind "task" out entirely).
  const tasksQuery = useQuery({
    queryKey: ['items'],
    queryFn: api.listItems,
    enabled: !!item,
    refetchInterval: 2000,
  });
  const childTasks = (tasksQuery.data ?? []).filter((i) => i.kind === 'task' && item != null && isChildOf(i, item));
  const graph = useTaskGraph(item?.kind === 'story' ? (id ?? null) : null, childTasks);
  const selectedNode = graph.nodes.find((n) => n.item.id === selectedTaskId) ?? null;
  const clarification = useClarification(item?.kind === 'story' ? item : undefined, 'build');

  // Keep the selected version pinned to the latest, but only while the user was already viewing
  // the latest - not just on mount (bug: viewing v1, a revision publishes v2, `selectedVersion`
  // (1) was never `> latestVersion` (2), so it silently stayed pinned to the stale v1 until a
  // full page reload reset `selectedVersion` to null and re-triggered this effect). A manually
  // selected older version (via the version dropdown) is left alone.
  const lastKnownLatestRef = useRef<number | null>(null);
  useEffect(() => {
    if (latestVersion <= 0) return;
    const wasOnLatest = selectedVersion === null || selectedVersion === lastKnownLatestRef.current;
    lastKnownLatestRef.current = latestVersion;
    if (wasOnLatest) {
      setSelectedVersion(latestVersion);
    }
  }, [latestVersion, selectedVersion]);

  const artifactQuery = useQuery({
    queryKey: ['artifact', id, selectedVersion],
    queryFn: () => api.getArtifact(id!, selectedVersion!),
    enabled: !!id && item?.kind === 'story' && selectedVersion != null,
    refetchInterval: 2000,
  });

  const prevArtifactQuery = useQuery({
    queryKey: ['artifact', id, (selectedVersion ?? 0) - 1],
    queryFn: () => api.getArtifact(id!, (selectedVersion ?? 0) - 1),
    enabled: !!id && item?.kind === 'story' && selectedVersion != null && selectedVersion > 1,
  });

  const reviewMdQuery = useQuery({
    queryKey: ['reviewmd', id],
    queryFn: () => api.getReviewMd(id!),
    enabled: !!id && item?.kind === 'story' && tab === 'reviewmd',
    refetchInterval: 2000,
  });

  const currentComments = artifactQuery.data?.comments ?? [];
  const projectGates = useProjectGates(item?.profile);
  const actions = useReviewActions(id ?? null, currentComments, item?.gate ?? null, item?.profile);

  const qualityQuery = useQuery({
    queryKey: ['quality', id],
    queryFn: () => api.getQuality(id!),
    enabled: !!id && item?.kind === 'story',
    retry: false,
    refetchInterval: 5000,
  });

  const storyDoc = useMemo(
    () => parseStoryDoc(artifactQuery.data?.storyMarkdown ?? ''),
    [artifactQuery.data?.storyMarkdown],
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

  const qualityFindings = useMemo(
    () => (qualityQuery.data ? parseQualityFindings(qualityQuery.data.reportMd ?? '') : []),
    [qualityQuery.data],
  );

  const versions = useMemo(() => {
    if (latestVersion <= 0) return [];
    return Array.from({ length: latestVersion }, (_, i) => i + 1);
  }, [latestVersion]);

  if (itemQuery.isLoading) {
    return (
      <Box>
        <Skeleton height="32px" width="320px" mb="3" />
        <Skeleton height="48px" mb="3" />
        <Skeleton height="240px" />
      </Box>
    );
  }
  if (itemQuery.isError) {
    return <ErrorCallout title="Failed to load item" error={itemQuery.error} />;
  }
  if (!item) return null;

  const failureBanner = item.gate?.lastFailure && (
    <Callout.Root color="red" mb="4">
      <Callout.Icon>
        <AlertTriangle size={15} />
      </Callout.Icon>
      <Callout.Text>
        {item.gate.lastFailure.step} failed: {item.gate.lastFailure.message}
      </Callout.Text>
      <Button
        size="1"
        variant="solid"
        color="red"
        loading={actions.retryStep.isPending}
        onClick={() => actions.retryStep.mutate()}
      >
        Retry
      </Button>
    </Callout.Root>
  );

  const waitBanner = item.agentWork?.phase === 'waiting-for-worker' && item.agentWork.workersOnline === 0 && (
    <Callout.Root color="amber" mb="4">
      <Callout.Icon>
        <AlertTriangle size={15} />
      </Callout.Icon>
      <Callout.Text>
        No build-worker is online — the {item.agentWork.kind} task for this story is waiting.{' '}
        <Link to="/agents">Run one locally</Link>.
      </Callout.Text>
    </Callout.Root>
  );

  if (item.kind === 'task')
    return (
      <>
        {failureBanner}
        {waitBanner}
        <TaskDetailPage item={item} />
      </>
    );
  if (item.kind === 'feature')
    return (
      <>
        {failureBanner}
        {waitBanner}
        <FeaturePage item={item} />
      </>
    );
  if (item.kind === 'release' || item.kind === 'bug')
    return (
      <>
        {failureBanner}
        {waitBanner}
        <BoardItemPage item={item} />
      </>
    );

  const rank = currentRank(item);
  const gateStage = item.gate?.stage;
  const gateOwner =
    gateStage === 'awaiting-G1'
      ? (projectGates.roles.G1 ?? []).join(' + ') || '—'
      : gateStage === 'awaiting-G2'
        ? (projectGates.roles.G2 ?? []).join(' + ') || '—'
        : gateStage === 'awaiting-G3'
          ? (projectGates.roles.G3 ?? []).join(' + ') || '—'
          : '—';
  const parentFeature =
    item.kind === 'story' ? (tasksQuery.data ?? []).find((i) => i.kind === 'feature' && isChildOf(item, i)) : undefined;

  return (
    <Box>
      <PageHeader
        title={item.title}
        badges={
          <>
            <span className="pill">{item.kind.toUpperCase()}</span>
            <StatusBadge state={item.canonicalState} pulse={item.activeRun != null} />
            <AgentActivityBadge run={item.activeRun} />
            {item.snapshot && <DemoSnapshotBadge snapshot={item.snapshot} />}
          </>
        }
        meta={
          <MetaItems
            items={[
              `Board ${item.boardId}`,
              <ProjectMetaLink profile={item.profile} />,
              ...(parentFeature
                ? [
                    <>
                      Feature: <Link to={`/items/${encodeURIComponent(parentFeature.id)}`}>{parentFeature.title}</Link>
                    </>,
                  ]
                : []),
            ]}
          />
        }
        subtitle={stageSubtitle(item)}
        actions={
          <>
            {versions.length > 0 && (
              <Select.Root
                value={selectedVersion != null ? String(selectedVersion) : undefined}
                onValueChange={(v) => setSelectedVersion(Number(v))}
              >
                <Select.Trigger style={{ minWidth: 110 }} />
                <Select.Content>
                  {versions.map((v) => (
                    <Select.Item key={v} value={String(v)}>
                      v{v}
                    </Select.Item>
                  ))}
                </Select.Content>
              </Select.Root>
            )}
            <Button
              variant="surface"
              onClick={() => {
                setTab('preview');
                requestCompose('@analyst ');
              }}
            >
              <Sparkles size={14} /> Ask agents
            </Button>
          </>
        }
      />

      <DeliveryMap item={item} />

      {failureBanner}
      {waitBanner}

      <ReviewBar item={item} actions={actions} onOpenRelease={() => setTab('release')} />

      <Surface
        value={tab}
        onValueChange={(v) => setTab(v as Tab)}
        tabs={[
          { value: 'clarify', label: 'Clarification', count: clarification.open.length },
          { value: 'preview', label: 'Preview' },
          { value: 'source', label: 'Source' },
          { value: 'diff', label: 'Diff', disabled: selectedVersion == null || selectedVersion <= 1, tooltip: 'Needs at least two versions' },
          { value: 'tasks', label: 'Tasks', count: childTasks.length },
          { value: 'quality', label: 'Quality' },
          { value: 'release', label: 'Release' },
          { value: 'activity', label: 'Activity' },
          { value: 'reviewmd', label: 'review.md' },
        ]}
        inspector={
          tab === 'clarify' ? (
            <ClarificationRail item={item} scope="build" />
          ) : tab === 'preview' ? (
            <>
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
                readOnly={item.snapshot != null}
                blocks={commentBlocks}
              />
              <div className="sidecard">
                <h3>Review context</h3>
                <div className="kv">
                  <span>Artifact</span>
                  <span>story.md · v{selectedVersion ?? latestVersion}</span>
                </div>
                <div className="kv">
                  <span>Hash</span>
                  <span>
                    <CopyHash hash={item.latestContentHash} />
                  </span>
                </div>
                <div className="kv">
                  <span>Gate owner</span>
                  <span>{gateOwner}</span>
                </div>
                <div className="kv">
                  <span>Next stage</span>
                  <span>{nextPhaseLabel(rank)}</span>
                </div>
              </div>
              <div className="sidecard quality-card">
                <div className="kv">
                  <h3>Quality summary</h3>
                  <button type="button" className="link" onClick={() => setTab('quality')}>
                    View details →
                  </button>
                </div>
                {qualityQuery.data ? (
                  <>
                    <div className={'score' + (qualityQuery.data.verdict === 'passed' ? '' : ' fail')}>
                      {qualityQuery.data.score ?? '—'}/100
                    </div>
                    <div className="qrow">
                      <span>
                        <i className="amber" /> {qualityFindings.length} finding{qualityFindings.length === 1 ? '' : 's'}
                      </span>
                      <span>
                        <i className={qualityQuery.data.verdict === 'passed' ? 'green' : 'red'} />{' '}
                        {qualityQuery.data.verdict === 'passed' ? 'Passed' : 'Failed'}
                      </span>
                    </div>
                  </>
                ) : (
                  <Text size="2" color="gray">
                    No quality report yet.
                  </Text>
                )}
              </div>
            </>
          ) : tab === 'tasks' && selectedNode ? (
            <TaskPane node={selectedNode} onClose={() => setSelectedTask(null)} />
          ) : undefined
        }
      >
        <Tabs.Content value="clarify">
          <ClarificationTab item={item} scope="build" />
        </Tabs.Content>

        <Tabs.Content value="preview">
          <ViewHead
            title="Story preview"
            sub="Select any block to add a review comment."
            aside={
              item.qualityVerdict === 'passed' ? (
                <span className="pill pass">
                  <Check size={13} /> Quality passed
                </span>
              ) : item.qualityVerdict === 'failed' ? (
                <span className="pill fail">Quality failed</span>
              ) : null
            }
          />
          {artifactQuery.isLoading && <Text color="gray">Loading version…</Text>}
          {artifactQuery.isError && <ErrorCallout title="Failed to load artifact" error={artifactQuery.error} />}
          {item.activeRun && !item.gate?.lastFailure && (
            <Callout.Root color="cyan" mb="4">
              <Callout.Icon>
                <Spinner size="1" />
              </Callout.Icon>
              <Callout.Text>
                {agentRunLabel(item.activeRun)}… the preview refreshes automatically when it finishes. Running for{' '}
                <RelativeTime iso={item.activeRun.startedAt} />.
              </Callout.Text>
            </Callout.Root>
          )}
          {artifactQuery.data && (
            <PreviewTab
              markdown={artifactQuery.data.storyMarkdown}
              reviews={artifactQuery.data.scenarioReviews}
              canReview={actions.g1RoleAllowed && item.snapshot == null}
              reviewing={actions.reviewScenario.isPending ? (actions.reviewScenario.variables?.scenario ?? null) : null}
              onReviewScenario={(scenario, status) =>
                actions.reviewScenario.mutate({ version: selectedVersion ?? latestVersion, scenario, status })
              }
              onLineSelect={(line, shift) => setDraftTarget((prev) => extendLineTarget(prev, line, shift))}
              onRangeSelect={(start, end) => setDraftTarget(formatLineTarget(start, end))}
              selectedRange={parseLineTarget(draftTarget)}
            />
          )}
        </Tabs.Content>

        <Tabs.Content value="source">
          <ViewHead title="Story source" sub="Version-controlled Markdown used by agents and evaluators." />
          {artifactQuery.data && <SourceTab markdown={artifactQuery.data.storyMarkdown} comments={currentComments} />}
        </Tabs.Content>

        <Tabs.Content value="diff">
          <ViewHead title="Revision diff" sub={`v${(selectedVersion ?? 1) - 1} → v${selectedVersion ?? 1}`} />
          {selectedVersion != null && selectedVersion > 1 && (
            <DiffTab
              oldMarkdown={prevArtifactQuery.data?.storyMarkdown ?? ''}
              newMarkdown={artifactQuery.data?.storyMarkdown ?? ''}
              oldLoading={prevArtifactQuery.isLoading}
              oldError={prevArtifactQuery.isError}
            />
          )}
        </Tabs.Content>

        <Tabs.Content value="tasks">
          <ViewHead
            title="Execution graph"
            sub="Waves group tasks that can build in parallel; a wave starts once every earlier wave is green."
            aside={childTasks.length > 0 ? <span className="pill">{childTasks.length} task{childTasks.length === 1 ? '' : 's'}</span> : undefined}
          />
          {childTasks.length === 0 ? (
            <EmptyState
              icon={<GitBranch size={28} />}
              title="No task graph yet"
              hint="Approve the story; the plan agent then inspects the repository and proposes a dependency-aware task graph."
            />
          ) : (
            <>
              <ExecutionGraph
                columns={graph.columns}
                selectedId={selectedTaskId}
                onSelect={(taskId) => setSelectedTask(taskId === selectedTaskId ? null : taskId)}
              />
              <BuildEvidence summary={graph.summary} />
            </>
          )}
        </Tabs.Content>

        <Tabs.Content value="quality">
          <ViewHead title="Quality evaluation" sub="Independent assessment of scope, clarity, and testability." />
          <QualityTab id={id!} />
        </Tabs.Content>

        <Tabs.Content value="release">
          <ViewHead title="Release pack & signatures" sub="Every release artifact is signed independently." />
          <ReleaseTab id={id!} readOnly={item.snapshot != null} />
        </Tabs.Content>

        <Tabs.Content value="activity">
          <ViewHead title="Activity" sub="Durable history of people, agents, gates, and artifacts." />
          <ActivityTab id={id!} snapshot={item.snapshot != null} />
        </Tabs.Content>

        <Tabs.Content value="reviewmd">
          <ViewHead title="review.md" sub="Committed human and agent review summary." />
          {reviewMdQuery.isError ? (
            <ErrorCallout title="Failed to load review.md" error={reviewMdQuery.error} />
          ) : (
            <ReviewMdTab loading={reviewMdQuery.isLoading} error={null} content={reviewMdQuery.data ?? null} />
          )}
        </Tabs.Content>
      </Surface>
    </Box>
  );
}
