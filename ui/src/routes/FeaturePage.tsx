import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { FileQuestion, MessageSquare } from 'lucide-react';
import { Avatar, Badge, Box, Card, Flex, Tabs, Text } from '@radix-ui/themes';
import { api } from '../api';
import type { ItemDetail } from '../types';
import { intentBadgeColor, isChildOf } from '../ui-utils';
import PageHeader, { MetaItems } from '../components/PageHeader';
import ProjectMetaLink from '../components/ProjectMetaLink';
import StatusBadge from '../components/StatusBadge';
import AgentActivityBadge from '../components/AgentActivityBadge';
import DemoSnapshotBadge from '../components/DemoSnapshotBadge';
import EmptyState from '../components/EmptyState';
import Surface from '../components/Surface';
import { useClarification } from '../review/useClarification';
import ClarificationTab from '../review/ClarificationTab';
import ClarificationRail from '../review/ClarificationRail';
import ActivityTab from '../review/ActivityTab';
import { stageSubtitle } from '../review/pipeline';

interface Props {
  item: ItemDetail;
}

export default function FeaturePage({ item }: Props) {
  const [tab, setTab] = useState(item.canonicalState === 'needs-clarification' ? 'clarify' : 'stories');
  const clarification = useClarification(item, 'intake');
  // The tab defaults off canonicalState, but this component doesn't remount as the live item
  // progresses through needs-clarification -> resolved -> ready-for-story (ReviewPage just
  // refetches the same item) - so follow canonicalState until the user manually picks a tab.
  const userPickedTab = useRef(false);
  useEffect(() => {
    if (userPickedTab.current) return;
    setTab(item.canonicalState === 'needs-clarification' ? 'clarify' : 'stories');
  }, [item.canonicalState]);
  function handleTabChange(v: string) {
    userPickedTab.current = true;
    setTab(v);
  }

  const itemsQuery = useQuery({
    queryKey: ['items'],
    queryFn: api.listItems,
    refetchInterval: 5000,
  });
  const stories = (itemsQuery.data ?? []).filter((i) => i.kind === 'story' && isChildOf(i, item));

  const boardCommentsQuery = useQuery({
    queryKey: ['board-comments', item.id],
    queryFn: () => api.getBoardComments(item.id),
    refetchInterval: 5000,
  });
  const boardComments = boardCommentsQuery.data ?? [];

  return (
    <Box>
      <PageHeader
        title={item.title}
        badges={
          <>
            <span className="pill">FEATURE</span>
            <StatusBadge state={item.canonicalState} />
            <AgentActivityBadge run={item.activeRun} />
            {item.snapshot && <DemoSnapshotBadge snapshot={item.snapshot} />}
          </>
        }
        meta={<MetaItems items={[`Board ${item.boardId}`, <ProjectMetaLink profile={item.profile} />]} />}
        subtitle={stageSubtitle(item)}
      />

      <Surface
        value={tab}
        onValueChange={handleTabChange}
        tabs={[
          { value: 'clarify', label: 'Clarification', count: clarification.open.length + (clarification.confirmation ? 1 : 0) },
          { value: 'stories', label: 'Stories', count: stories.length },
          { value: 'activity', label: 'Agent activity' },
          { value: 'board', label: 'Board activity' },
        ]}
        inspector={tab === 'clarify' ? <ClarificationRail item={item} scope="intake" /> : undefined}
      >
        <Tabs.Content value="clarify">
          <ClarificationTab item={item} scope="intake" />
        </Tabs.Content>
        <Tabs.Content value="stories">
          {stories.length === 0 ? (
            <EmptyState
              icon={<FileQuestion size={28} />}
              title="No story yet"
              hint="The PO agent drafts a story once every clarification question is answered or parked."
            />
          ) : (
            <Flex direction="column" gap="2">
              {stories.map((s) => (
                <Flex key={s.id} align="center" justify="between" gap="2">
                  <Link to={`/items/${s.id}`}>{s.title}</Link>
                  <Flex gap="1" align="center">
                    <StatusBadge state={s.canonicalState} />
                    <AgentActivityBadge run={s.activeRun} />
                  </Flex>
                </Flex>
              ))}
            </Flex>
          )}
        </Tabs.Content>

        <Tabs.Content value="activity">
          <ActivityTab id={item.id} snapshot={item.snapshot != null} />
        </Tabs.Content>

        <Tabs.Content value="board">
          {boardComments.length === 0 ? (
            <EmptyState icon={<MessageSquare size={28} />} title="No board activity yet" />
          ) : (
            <Flex direction="column" gap="2">
              {boardComments.map((c) => (
                <Card key={c.id} size="2">
                  <Flex align="center" gap="2" mb="1" wrap="wrap">
                    <Avatar size="1" radius="full" fallback={c.by[0]?.toUpperCase() ?? '?'} />
                    <Text size="2" weight="bold">
                      {c.by}
                    </Text>
                    <Badge color="gray" variant="soft">
                      {c.role}
                    </Badge>
                    <Badge color={intentBadgeColor(c.intent.toLowerCase())}>{c.intent.toLowerCase()}</Badge>
                  </Flex>
                  <Text size="2" style={{ whiteSpace: 'pre-wrap' }}>
                    {c.text}
                  </Text>
                </Card>
              ))}
            </Flex>
          )}
        </Tabs.Content>
      </Surface>
    </Box>
  );
}
