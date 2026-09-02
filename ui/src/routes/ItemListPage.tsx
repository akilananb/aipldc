import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ChevronDown, ChevronRight, Inbox, Search } from 'lucide-react';
import { Badge, Box, Flex, Heading, IconButton, SegmentedControl, Skeleton, Table, Text, TextField } from '@radix-ui/themes';
import { api } from '../api';
import type { ItemSummary } from '../types';
import { useIdentity } from '../identity';
import { GATE_ROLES } from '../gates';
import ErrorCallout from '../components/ErrorCallout';
import EmptyState from '../components/EmptyState';
import StatusBadge from '../components/StatusBadge';
import RelativeTime from '../components/RelativeTime';
import { stateBadgeColor } from '../ui-utils';
import QualityIcon from '../components/QualityIcon';

type KindFilter = 'all' | 'feature' | 'story';

const ATTENTION_GATE: Record<string, keyof typeof GATE_ROLES> = {
  'awaiting-G1': 'G1',
  'awaiting-G2': 'G2',
  'awaiting-G3': 'G3',
};

export default function ItemListPage() {
  const identity = useIdentity();
  const navigate = useNavigate();
  const itemsQuery = useQuery({
    queryKey: ['items'],
    queryFn: api.listItems,
    refetchInterval: 2000,
  });

  const [search, setSearch] = useState('');
  const [kindFilter, setKindFilter] = useState<KindFilter>('all');
  const [activeStates, setActiveStates] = useState<Set<string>>(new Set());
  const [collapsedFeatures, setCollapsedFeatures] = useState<Set<string>>(new Set());

  // Tasks are children of a story (see WorkItemEntity.parentId) - drill into a story's Tasks
  // section (ReviewPage) instead of cluttering the top-level list (ItemListPage filters kind
  // "task" out entirely).
  const topLevelItems = useMemo(() => (itemsQuery.data ?? []).filter((item) => item.kind !== 'task'), [itemsQuery.data]);

  const distinctStates = useMemo(
    () => Array.from(new Set(topLevelItems.map((i) => i.canonicalState))).sort(),
    [topLevelItems],
  );

  const filteredItems = useMemo(() => {
    return topLevelItems.filter((item) => {
      if (kindFilter !== 'all' && item.kind !== kindFilter) return false;
      if (activeStates.size > 0 && !activeStates.has(item.canonicalState)) return false;
      if (search.trim() && !item.title.toLowerCase().includes(search.trim().toLowerCase())) return false;
      return true;
    });
  }, [topLevelItems, kindFilter, activeStates, search]);

  // Group stories under their parent feature (accordion-style row nesting) when no kind filter
  // narrows the view; a story whose parent feature didn't pass the current filters renders as
  // its own top-level row instead of disappearing.
  const rows = useMemo<Array<{ item: ItemSummary; children: ItemSummary[] }>>(() => {
    if (kindFilter !== 'all') {
      return filteredItems.map((item) => ({ item, children: [] }));
    }
    const features = filteredItems.filter((i) => i.kind === 'feature');
    const stories = filteredItems.filter((i) => i.kind === 'story');
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
    const grouped = [
      ...features.map((f) => ({ item: f, children: childrenByFeature.get(f.boardId) ?? [] })),
      ...orphanStories.map((s) => ({ item: s, children: [] as ItemSummary[] })),
    ];
    return grouped.sort((a, b) => new Date(b.item.updatedAt).getTime() - new Date(a.item.updatedAt).getTime());
  }, [filteredItems, kindFilter]);

  function toggleFeatureCollapsed(boardId: string) {
    setCollapsedFeatures((prev) => {
      const next = new Set(prev);
      if (next.has(boardId)) next.delete(boardId);
      else next.add(boardId);
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

  function needsAttention(canonicalState: string): boolean {
    const gate = ATTENTION_GATE[canonicalState];
    return gate != null && GATE_ROLES[gate].includes(identity.role);
  }

  return (
    <Box>
      <Heading size="4" mb="4">
        Work items
      </Heading>

      <Flex gap="3" mb="4" align="center" wrap="wrap">
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

        <SegmentedControl.Root value={kindFilter} onValueChange={(v) => setKindFilter(v as KindFilter)}>
          <SegmentedControl.Item value="all">All</SegmentedControl.Item>
          <SegmentedControl.Item value="feature">Features</SegmentedControl.Item>
          <SegmentedControl.Item value="story">Stories</SegmentedControl.Item>
        </SegmentedControl.Root>

        <Flex gap="2" wrap="wrap">
          {distinctStates.map((state) => (
            <Badge
              key={state}
              color={activeStates.has(state) ? stateBadgeColor(state) : 'gray'}
              variant={activeStates.has(state) ? 'solid' : 'soft'}
              style={{ cursor: 'pointer' }}
              onClick={() => toggleState(state)}
            >
              {state}
            </Badge>
          ))}
        </Flex>
      </Flex>

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

      {itemsQuery.isSuccess && filteredItems.length > 0 && (
        <Table.Root variant="surface">
          <Table.Header>
            <Table.Row>
              <Table.ColumnHeaderCell>Title</Table.ColumnHeaderCell>
              <Table.ColumnHeaderCell>Kind</Table.ColumnHeaderCell>
              <Table.ColumnHeaderCell>State</Table.ColumnHeaderCell>
              <Table.ColumnHeaderCell>Quality</Table.ColumnHeaderCell>
              <Table.ColumnHeaderCell>Attention</Table.ColumnHeaderCell>
              <Table.ColumnHeaderCell>Updated</Table.ColumnHeaderCell>
            </Table.Row>
          </Table.Header>
          <Table.Body>
            {rows.map(({ item, children }) => {
              const collapsed = collapsedFeatures.has(item.boardId);
              return (
                <>
                  {renderRow(item, { hasChildren: children.length > 0, collapsed })}
                  {children.length > 0 && !collapsed && children.map((child) => renderRow(child, { indent: true }))}
                </>
              );
            })}
          </Table.Body>
        </Table.Root>
      )}
    </Box>
  );

  function renderRow(
    item: ItemSummary,
    opts: { indent?: boolean; hasChildren?: boolean; collapsed?: boolean } = {},
  ) {
    const unavailable = item.title === '(unavailable)';
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
                aria-label={opts.collapsed ? 'Expand stories' : 'Collapse stories'}
                onClick={(e) => {
                  e.stopPropagation();
                  toggleFeatureCollapsed(item.boardId);
                }}
              >
                {opts.collapsed ? <ChevronRight size={14} /> : <ChevronDown size={14} />}
              </IconButton>
            ) : (
              <Box style={{ width: 24, flexShrink: 0 }} />
            )}
            <Box style={{ paddingLeft: opts.indent ? 20 : 0, minWidth: 0 }}>
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
            </Box>
          </Flex>
        </Table.RowHeaderCell>
        <Table.Cell>{item.kind}</Table.Cell>
        <Table.Cell>
          <StatusBadge state={item.canonicalState} />
        </Table.Cell>
        <Table.Cell>
          <QualityIcon verdict={item.qualityVerdict} />
        </Table.Cell>
        <Table.Cell>{needsAttention(item.canonicalState) && <Badge color="amber">needs your review</Badge>}</Table.Cell>
        <Table.Cell>
          <RelativeTime iso={item.updatedAt} />
        </Table.Cell>
      </Table.Row>
    );
  }
}
