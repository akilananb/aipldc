import { Fragment, useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ChevronDown, ChevronRight, Inbox, Search } from 'lucide-react';
import { Badge, Box, Button, Flex, IconButton, Skeleton, Table, Tabs, Text, TextField } from '@radix-ui/themes';
import { toast } from 'sonner';
import { api, errorMessage } from '../api';
import type { ItemSummary } from '../types';
import { useIdentity } from '../identity';
import ErrorCallout from '../components/ErrorCallout';
import EmptyState from '../components/EmptyState';
import StatusBadge from '../components/StatusBadge';
import AgentActivityBadge from '../components/AgentActivityBadge';
import RelativeTime from '../components/RelativeTime';
import DemoSnapshotBadge from '../components/DemoSnapshotBadge';
import PageHeader from '../components/PageHeader';
import Surface from '../components/Surface';
import ViewHead from '../components/ViewHead';
import { needsAttention, statePillVariant } from '../ui-utils';
import QualityIcon from '../components/QualityIcon';

type KindFilter = 'all' | 'feature' | 'story';
type View = 'items' | 'running' | 'attention';

type Row = { item: ItemSummary; children: ItemSummary[] };
type StageGroup = {
  key: string;
  label: string;
  order: number;
  sourceRef: string;
  replay: boolean;
  primary: ItemSummary;
  secondary: ItemSummary[];
};

export default function ItemListPage() {
  const identity = useIdentity();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [params] = useSearchParams();
  const view: View = params.get('view') === 'running' ? 'running' : params.get('view') === 'attention' ? 'attention' : 'items';

  const itemsQuery = useQuery({
    queryKey: ['items'],
    queryFn: api.listItems,
    refetchInterval: 2000,
  });

  const demoStatusQuery = useQuery({
    queryKey: ['demo'],
    queryFn: api.getDemoStatus,
    refetchInterval: 2000,
  });

  const startLiveMutation = useMutation({
    mutationFn: api.startLiveDemo,
    onSuccess: ({ itemId }) => {
      queryClient.invalidateQueries({ queryKey: ['items'] });
      queryClient.invalidateQueries({ queryKey: ['demo'] });
      navigate(`/items/${encodeURIComponent(itemId)}`);
    },
    onError: (error) => toast.error(errorMessage(error)),
  });

  useEffect(() => {
    document.title = 'Work items · eLoop.ai';
  }, []);

  const [search, setSearch] = useState('');
  const [kindFilter, setKindFilter] = useState<KindFilter>('all');
  const [activeStates, setActiveStates] = useState<Set<string>>(new Set());
  const [expandedStages, setExpandedStages] = useState<Set<string>>(new Set());

  // Tasks are children of a story (see WorkItemEntity.parentId) - drill into a story's Tasks
  // section (ReviewPage) instead of cluttering the top-level list (ItemListPage filters kind
  // "task" out entirely).
  const topLevelItems = useMemo(() => (itemsQuery.data ?? []).filter((item) => item.kind !== 'task'), [itemsQuery.data]);

  const distinctStates = useMemo(
    () => Array.from(new Set(topLevelItems.map((i) => i.canonicalState))).sort(),
    [topLevelItems],
  );

  const filteredItems = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return topLevelItems.filter((item) => {
      if (kindFilter !== 'all' && item.kind !== kindFilter) return false;
      if (activeStates.size > 0 && !activeStates.has(item.canonicalState)) return false;
      if (needle && !item.title.toLowerCase().includes(needle) && !item.boardId.toLowerCase().includes(needle)) return false;
      if (view === 'running' && item.activeRun == null) return false;
      if (view === 'attention' && !(item.snapshot == null && needsAttention(item.canonicalState, identity.role))) return false;
      return true;
    });
  }, [topLevelItems, kindFilter, activeStates, search, view, identity.role]);

  // The total stage count for "Step N of M" must come from the FULL unfiltered catalog, never
  // from the currently-filtered stageGroups.length - otherwise a state/search filter that hides
  // a stage would also shrink the denominator (e.g. "Step 7 of 3").
  const totalStageCount = useMemo(() => {
    const keys = new Set<string>();
    for (const item of itemsQuery.data ?? []) {
      if (item.snapshot) keys.add(item.snapshot.key);
    }
    return keys.size;
  }, [itemsQuery.data]);

  function nestStoriesUnderFeatures(items: ItemSummary[]): Row[] {
    const features = items.filter((i) => i.kind === 'feature');
    const stories = items.filter((i) => i.kind === 'story');
    const others = items.filter((i) => i.kind !== 'feature' && i.kind !== 'story');
    const featureBoardIds = new Set(features.map((f) => f.boardId));
    const childrenByFeature = new Map<string, ItemSummary[]>();
    const orphanStories: ItemSummary[] = [];
    for (const story of stories) {
      if (story.parentId && featureBoardIds.has(story.parentId)) {
        const list = childrenByFeature.get(story.parentId) ?? [];
        list.push(story);
        childrenByFeature.set(story.parentId, list);
      } else {
        orphanStories.push(story);
      }
    }
    return [
      ...features.map((f) => ({ item: f, children: childrenByFeature.get(f.boardId) ?? [] })),
      ...orphanStories.map((s) => ({ item: s, children: [] as ItemSummary[] })),
      ...others.map((o) => ({ item: o, children: [] as ItemSummary[] })),
    ];
  }

  // Filtered/narrowed view (Features-only or Stories-only): a plain flat list, most-recently
  // updated first - the kind filter itself already narrows to one row type per stage, so no
  // primary/secondary collapsing is needed here.
  const flatRows = useMemo<Row[]>(() => {
    if (kindFilter === 'all') return [];
    return filteredItems
      .map((item) => ({ item, children: [] as ItemSummary[] }))
      .sort((a, b) => new Date(b.item.updatedAt).getTime() - new Date(a.item.updatedAt).getTime());
  }, [filteredItems, kindFilter]);

  // Default "all" view: exactly one clickable row per curated stage (the story once it exists,
  // else the feature) so the guided walkthrough reads as 8 ordered steps, not ~40 rows that all
  // repeat the same feature/story title. The feature/release/bug siblings for that stage collapse
  // under the step by default; expand to reach them directly (e.g. jump straight to the release
  // pack or the monitor bug without opening the story first).
  const stageGroups = useMemo<StageGroup[]>(() => {
    if (kindFilter !== 'all') return [];
    const byKey = new Map<string, ItemSummary[]>();
    for (const item of filteredItems) {
      if (!item.snapshot) continue;
      const list = byKey.get(item.snapshot.key) ?? [];
      list.push(item);
      byKey.set(item.snapshot.key, list);
    }
    return Array.from(byKey.entries())
      .map(([key, items]) => {
        const snap = items[0].snapshot!;
        const primary = items.find((i) => i.kind === 'story') ?? items.find((i) => i.kind === 'feature') ?? items[0];
        const secondary = items.filter((i) => i.id !== primary.id);
        return { key, label: snap.label, order: snap.order, sourceRef: snap.sourceRef, replay: snap.replay, primary, secondary };
      })
      .sort((a, b) => a.order - b.order);
  }, [filteredItems, kindFilter]);

  const liveRows = useMemo<Row[]>(() => {
    if (kindFilter !== 'all') return [];
    const liveItems = filteredItems.filter((i) => i.snapshot == null);
    return nestStoriesUnderFeatures(liveItems).sort(
      (a, b) => new Date(b.item.updatedAt).getTime() - new Date(a.item.updatedAt).getTime(),
    );
  }, [filteredItems, kindFilter]);

  const [collapsedFeatures, setCollapsedFeatures] = useState<Set<string>>(new Set());

  function toggleFeatureCollapsed(boardId: string) {
    setCollapsedFeatures((prev) => {
      const next = new Set(prev);
      if (next.has(boardId)) next.delete(boardId);
      else next.add(boardId);
      return next;
    });
  }

  function toggleStageExpanded(key: string) {
    setExpandedStages((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  function toggleState(state: string) {
    setActiveStates((prev) => {
      const next = new Set(prev);
      if (next.has(state)) next.delete(state);
      else next.add(state);
      return next;
    });
  }

  const demoEnabled = demoStatusQuery.data?.enabled ?? false;
  const liveItem = demoEnabled
    ? (itemsQuery.data ?? []).find((i) => i.id === demoStatusQuery.data?.liveItemId)
    : undefined;
  const liveIsNew = liveItem == null || liveItem.canonicalState === 'new';

  const title = view === 'running' ? 'Agent runs' : view === 'attention' ? 'Needs your review' : 'Work items';
  const subtitle =
    view === 'running'
      ? 'Items with an agent currently working.'
      : view === 'attention'
        ? `Items waiting on a gate that ${identity.role} checks.`
        : 'Guided walkthrough stages plus the live demo you can run yourself.';

  return (
    <Box>
      <PageHeader
        title={title}
        subtitle={subtitle}
        badges={<span className="pill">WORKSPACE</span>}
        actions={
          <TextField.Root
            placeholder="Search by title…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            style={{ width: 260 }}
          >
            <TextField.Slot>
              <Search size={14} />
            </TextField.Slot>
          </TextField.Root>
        }
      />

      <Surface
        value={kindFilter}
        onValueChange={(v) => setKindFilter(v as KindFilter)}
        tabs={[
          { value: 'all', label: 'All' },
          { value: 'feature', label: 'Features' },
          { value: 'story', label: 'Stories' },
        ]}
      >
        <Tabs.Content value={kindFilter}>{renderBody()}</Tabs.Content>
      </Surface>
    </Box>
  );

  function renderBody() {
    return (
      <Box>
        {distinctStates.length > 0 && (
          <Flex gap="2" mb="4" wrap="wrap">
            {distinctStates.map((state) => {
              const active = activeStates.has(state);
              const variant = statePillVariant(state);
              return (
                <span
                  key={state}
                  className={`pill${active && variant ? ` ${variant}` : ''}`}
                  role="button"
                  tabIndex={0}
                  style={{ cursor: 'pointer', opacity: active ? 1 : 0.6 }}
                  onClick={() => toggleState(state)}
                >
                  {state}
                </span>
              );
            })}
          </Flex>
        )}

        {itemsQuery.isLoading && (
          <Flex direction="column" gap="2">
            {Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} height="32px" />
            ))}
          </Flex>
        )}

        {itemsQuery.isError && <ErrorCallout title="Failed to load items" error={itemsQuery.error} />}

        {itemsQuery.isSuccess && filteredItems.length === 0 && (
          <EmptyState
            icon={<Inbox size={28} />}
            title="No work items"
            hint="Items appear here when a feature is created on the board."
          />
        )}

        {itemsQuery.isSuccess && filteredItems.length > 0 && kindFilter !== 'all' && (
          <Table.Root variant="ghost" size="1">
            <Table.Header>{tableHeader()}</Table.Header>
            <Table.Body>
              {flatRows.map(({ item, children }) => {
                const collapsed = collapsedFeatures.has(item.boardId);
                return (
                  <Fragment key={item.id}>
                    {renderRow(item, { hasChildren: children.length > 0, collapsed })}
                    {children.length > 0 && !collapsed && children.map((child) => renderRow(child, { indent: true }))}
                  </Fragment>
                );
              })}
            </Table.Body>
          </Table.Root>
        )}

        {itemsQuery.isSuccess && filteredItems.length > 0 && kindFilter === 'all' && (
          <Flex direction="column" gap="5">
            {stageGroups.length > 0 && (
              <Box>
                <ViewHead title={`Guided walkthrough — ${totalStageCount} frozen stages (read-only)`} />
                <Table.Root variant="ghost" size="1">
                  <Table.Header>{tableHeader()}</Table.Header>
                  <Table.Body>
                    {stageGroups.map((group) => (
                      <Fragment key={group.key}>
                        {renderStageHeader(group)}
                        {renderRow(group.primary, {
                          indent: true,
                          hasChildren: group.secondary.length > 0,
                          collapsed: !expandedStages.has(group.key),
                          onToggle: () => toggleStageExpanded(group.key),
                          compactBadge: true,
                        })}
                        {expandedStages.has(group.key) &&
                          group.secondary.map((item) => renderRow(item, { indent: true, subIndent: true, compactBadge: true }))}
                      </Fragment>
                    ))}
                  </Table.Body>
                </Table.Root>
              </Box>
            )}

            {liveRows.length > 0 && (
              <Box>
                <ViewHead title="Live demo — run this one yourself" />
                <Table.Root variant="ghost" size="1">
                  <Table.Header>{tableHeader()}</Table.Header>
                  <Table.Body>
                    {liveRows.map(({ item, children }) => {
                      const collapsed = collapsedFeatures.has(item.boardId);
                      const isPinnedLive = item.id === liveItem?.id;
                      return (
                        <Fragment key={item.id}>
                          {renderRow(item, {
                            hasChildren: children.length > 0,
                            collapsed,
                            liveAction: isPinnedLive,
                          })}
                          {children.length > 0 && !collapsed && children.map((child) => renderRow(child, { indent: true }))}
                        </Fragment>
                      );
                    })}
                  </Table.Body>
                </Table.Root>
              </Box>
            )}
          </Flex>
        )}
      </Box>
    );
  }

  function tableHeader() {
    return (
      <Table.Row>
        <Table.ColumnHeaderCell>Title</Table.ColumnHeaderCell>
        <Table.ColumnHeaderCell>Kind</Table.ColumnHeaderCell>
        <Table.ColumnHeaderCell>State</Table.ColumnHeaderCell>
        <Table.ColumnHeaderCell>Quality</Table.ColumnHeaderCell>
        <Table.ColumnHeaderCell>Attention</Table.ColumnHeaderCell>
        <Table.ColumnHeaderCell>Updated</Table.ColumnHeaderCell>
      </Table.Row>
    );
  }

  function renderStageHeader(group: StageGroup) {
    return (
      <Table.Row style={{ background: 'var(--accent-a3)' }}>
        <Table.Cell colSpan={6}>
          <Flex align="center" gap="2">
            <Badge size="2" variant="solid">
              Step {group.order} of {totalStageCount}
            </Badge>
            <Text weight="bold">{group.label}</Text>
            {group.replay && (
              <Badge color="amber" variant="soft">
                Replay evidence
              </Badge>
            )}
          </Flex>
        </Table.Cell>
      </Table.Row>
    );
  }

  function renderRow(
    item: ItemSummary,
    opts: {
      indent?: boolean;
      subIndent?: boolean;
      hasChildren?: boolean;
      collapsed?: boolean;
      onToggle?: () => void;
      compactBadge?: boolean;
      liveAction?: boolean;
    } = {},
  ) {
    const unavailable = item.title === '(unavailable)';
    const parentStory =
      (item.kind === 'release' || item.kind === 'bug') && item.parentId
        ? (itemsQuery.data ?? []).find((i) => i.kind === 'story' && i.boardId === item.parentId)
        : undefined;
    const indentPx = opts.subIndent ? 40 : opts.indent ? 20 : 0;
    return (
      <Table.Row
        key={item.id}
        style={{ cursor: 'pointer' }}
        onClick={() => navigate(`/items/${encodeURIComponent(item.id)}`)}
      >
        <Table.RowHeaderCell>
          <Flex align="center" gap="1">
            {opts.hasChildren ? (
              <IconButton
                size="1"
                variant="ghost"
                color="gray"
                aria-label={opts.collapsed ? 'Show feature/release/bug for this stage' : 'Hide feature/release/bug for this stage'}
                onClick={(e) => {
                  e.stopPropagation();
                  if (opts.onToggle) opts.onToggle();
                  else toggleFeatureCollapsed(item.boardId);
                }}
              >
                {opts.collapsed ? <ChevronRight size={14} /> : <ChevronDown size={14} />}
              </IconButton>
            ) : (
              <Box style={{ width: 24, flexShrink: 0 }} />
            )}
            <Box style={{ paddingLeft: indentPx, minWidth: 0 }}>
              <Link
                to={`/items/${encodeURIComponent(item.id)}`}
                onClick={(e) => e.stopPropagation()}
                style={{ color: 'inherit', textDecoration: 'none' }}
              >
                <Text
                  weight="bold"
                  color={unavailable ? 'gray' : undefined}
                  style={unavailable ? { fontStyle: 'italic' } : undefined}
                >
                  {unavailable ? 'Untitled — board item missing' : item.title}
                </Text>
              </Link>
              <Text as="div" size="1" color="gray">
                board {item.boardId}
              </Text>
              {parentStory && (
                <Text as="div" size="1" color="gray">
                  parent story:{' '}
                  <Link to={`/items/${encodeURIComponent(parentStory.id)}`} onClick={(e) => e.stopPropagation()}>
                    {parentStory.title}
                  </Link>
                </Text>
              )}
              {item.snapshot && (
                <Box mt="1">
                  <DemoSnapshotBadge snapshot={item.snapshot} compact={opts.compactBadge} />
                </Box>
              )}
              {opts.liveAction && (
                <Box mt="1">
                  {liveIsNew ? (
                    <Button
                      size="1"
                      onClick={(e) => {
                        e.stopPropagation();
                        startLiveMutation.mutate();
                      }}
                      loading={startLiveMutation.isPending}
                      disabled={startLiveMutation.isPending}
                    >
                      Start live restaurant demo
                    </Button>
                  ) : (
                    <Button
                      size="1"
                      variant="soft"
                      onClick={(e) => {
                        e.stopPropagation();
                        navigate(`/items/${encodeURIComponent(item.id)}`);
                      }}
                    >
                      Open live restaurant demo
                    </Button>
                  )}
                </Box>
              )}
            </Box>
          </Flex>
        </Table.RowHeaderCell>
        <Table.Cell>{item.kind}</Table.Cell>
        <Table.Cell>
          <Flex gap="1" align="center" wrap="wrap">
            <StatusBadge state={item.canonicalState} />
            <AgentActivityBadge run={item.activeRun} />
          </Flex>
        </Table.Cell>
        <Table.Cell>
          {item.kind === 'story' && <QualityIcon verdict={item.qualityVerdict} />}
        </Table.Cell>
        <Table.Cell>{item.snapshot == null && needsAttention(item.canonicalState, identity.role) && <span className="pill review">needs your review</span>}</Table.Cell>
        <Table.Cell>
          <RelativeTime iso={item.updatedAt} />
        </Table.Cell>
      </Table.Row>
    );
  }
}
