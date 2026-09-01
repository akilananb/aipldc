import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ListTodo } from 'lucide-react';
import { Badge, Box, Select, Skeleton, Table, Tabs, Text, Tooltip } from '@radix-ui/themes';
import { api } from '../api';
import type { CommentIntent } from '../types';
import { extendLineTarget, formatLineTarget, parseLineTarget } from '../ui-utils';
import PageHeader from '../components/PageHeader';
import StatusBadge from '../components/StatusBadge';
import ErrorCallout from '../components/ErrorCallout';
import EmptyState from '../components/EmptyState';
import { useReviewActions } from '../review/useReviewActions';
import GatePanel from '../review/GatePanel';
import CommentPanel from '../review/CommentPanel';
import PreviewTab from '../review/PreviewTab';
import SourceTab from '../review/SourceTab';
import DiffTab from '../review/DiffTab';
import ReviewMdTab from '../review/ReviewMdTab';
import ReleaseTab from '../review/ReleaseTab';
import TaskDetailPage from './TaskDetailPage';
import FeaturePage from './FeaturePage';

type Tab = 'preview' | 'source' | 'diff' | 'tasks' | 'release' | 'reviewmd';

export default function ReviewPage() {
  const { id } = useParams<{ id: string }>();

  const [selectedVersion, setSelectedVersion] = useState<number | null>(null);
  const [tab, setTab] = useState<Tab>('preview');
  const [draftTarget, setDraftTarget] = useState<string | null>(null);

  const itemQuery = useQuery({
    queryKey: ['item', id],
    queryFn: () => api.getItem(id!),
    enabled: !!id,
    refetchInterval: 2000,
  });

  const item = itemQuery.data;
  const latestVersion = item?.latestVersion ?? 0;

  // Tasks are their own work_items rows (kind "task", parentId = this story's boardId, see
  // BoardSideEffectsImpl#publishTasks) - drilled into here instead of cluttering the top-level
  // list (ItemListPage filters kind "task" out entirely).
  const tasksQuery = useQuery({
    queryKey: ['items'],
    queryFn: api.listItems,
    enabled: !!item,
    refetchInterval: 2000,
  });
  const childTasks = (tasksQuery.data ?? []).filter((i) => i.kind === 'task' && i.parentId === item?.boardId);

  // Keep the selected version pinned to the latest once we know it.
  useEffect(() => {
    if (latestVersion <= 0) return;
    if (selectedVersion === null) {
      setSelectedVersion(latestVersion);
    } else if (selectedVersion > latestVersion) {
      setSelectedVersion(latestVersion);
    }
  }, [latestVersion, selectedVersion]);

  const artifactQuery = useQuery({
    queryKey: ['artifact', id, selectedVersion],
    queryFn: () => api.getArtifact(id!, selectedVersion!),
    enabled: !!id && selectedVersion != null,
    refetchInterval: 2000,
  });

  const prevArtifactQuery = useQuery({
    queryKey: ['artifact', id, (selectedVersion ?? 0) - 1],
    queryFn: () => api.getArtifact(id!, (selectedVersion ?? 0) - 1),
    enabled: !!id && selectedVersion != null && selectedVersion > 1,
  });

  const reviewMdQuery = useQuery({
    queryKey: ['reviewmd', id],
    queryFn: () => api.getReviewMd(id!),
    enabled: !!id && tab === 'reviewmd',
    refetchInterval: 2000,
  });

  const currentComments = artifactQuery.data?.comments ?? [];
  const actions = useReviewActions(id ?? null, currentComments, item?.gate ?? null);

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
  if (item.kind === 'task') return <TaskDetailPage item={item} />;
  if (item.kind === 'feature') return <FeaturePage item={item} />;

  return (
    <Box>
      <PageHeader
        backTo={{ to: '/', label: 'Items' }}
        title={item.title}
        badges={
          <>
            <Badge color="gray">{item.kind}</Badge>
            <StatusBadge state={item.canonicalState} />
          </>
        }
        meta={`board ${item.boardId} · profile ${item.profile}`}
        actions={
          versions.length > 0 && (
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
          )
        }
      />

      <Box mb="4">
        <GatePanel item={item} actions={actions} />
      </Box>

      <Tabs.Root value={tab} onValueChange={(v) => setTab(v as Tab)}>
        <Tabs.List>
          <Tabs.Trigger value="preview">Preview</Tabs.Trigger>
          <Tabs.Trigger value="source">Source</Tabs.Trigger>
          {selectedVersion == null || selectedVersion <= 1 ? (
            <Tooltip content="Needs at least two versions">
              <Tabs.Trigger value="diff" disabled>
                Diff
              </Tabs.Trigger>
            </Tooltip>
          ) : (
            <Tabs.Trigger value="diff">Diff</Tabs.Trigger>
          )}
          <Tabs.Trigger value="tasks">Tasks ({childTasks.length})</Tabs.Trigger>
          <Tabs.Trigger value="release">Release</Tabs.Trigger>
          <Tabs.Trigger value="reviewmd">review.md</Tabs.Trigger>
        </Tabs.List>

        <Box pt="4">
          <Tabs.Content value="preview">
            {artifactQuery.isLoading && <Text color="gray">Loading version…</Text>}
            {artifactQuery.isError && <ErrorCallout title="Failed to load artifact" error={artifactQuery.error} />}
            {artifactQuery.data && (
              <div className="review-layout">
                <Box style={{ minWidth: 0 }}>
                  <PreviewTab
                    markdown={artifactQuery.data.storyMarkdown}
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

          <Tabs.Content value="source">
            {artifactQuery.data && (
              <SourceTab markdown={artifactQuery.data.storyMarkdown} comments={currentComments} />
            )}
          </Tabs.Content>

          <Tabs.Content value="diff">
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
            {childTasks.length === 0 ? (
              <EmptyState icon={<ListTodo size={28} />} title="No tasks yet" hint="Tasks appear after the plan agent runs." />
            ) : (
              <Table.Root variant="surface" size="1">
                <Table.Header>
                  <Table.Row>
                    <Table.ColumnHeaderCell>Title</Table.ColumnHeaderCell>
                    <Table.ColumnHeaderCell>State</Table.ColumnHeaderCell>
                    <Table.ColumnHeaderCell>Updated</Table.ColumnHeaderCell>
                  </Table.Row>
                </Table.Header>
                <Table.Body>
                  {childTasks.map((task) => (
                    <Table.Row key={task.id}>
                      <Table.Cell>
                        <Link to={`/items/${task.id}`}>{task.title}</Link>
                      </Table.Cell>
                      <Table.Cell>
                        <StatusBadge state={task.canonicalState} />
                      </Table.Cell>
                      <Table.Cell>
                        <Text size="2" color="gray">
                          {new Date(task.updatedAt).toLocaleString()}
                        </Text>
                      </Table.Cell>
                    </Table.Row>
                  ))}
                </Table.Body>
              </Table.Root>
            )}
          </Tabs.Content>

          <Tabs.Content value="release">
            <ReleaseTab id={id!} />
          </Tabs.Content>

          <Tabs.Content value="reviewmd">
            {reviewMdQuery.isError ? (
              <ErrorCallout title="Failed to load review.md" error={reviewMdQuery.error} />
            ) : (
              <ReviewMdTab loading={reviewMdQuery.isLoading} error={null} content={reviewMdQuery.data ?? null} />
            )}
          </Tabs.Content>
        </Box>
      </Tabs.Root>
    </Box>
  );
}
