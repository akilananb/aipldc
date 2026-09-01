import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { FileQuestion, MessageSquare } from 'lucide-react';
import { Avatar, Badge, Box, Card, Flex, Text } from '@radix-ui/themes';
import { api } from '../api';
import type { ItemDetail } from '../types';
import { intentBadgeColor } from '../ui-utils';
import PageHeader from '../components/PageHeader';
import StatusBadge from '../components/StatusBadge';
import EmptyState from '../components/EmptyState';
import Panel from '../components/Panel';
import GateProgress from '../review/GateProgress';

interface Props {
  item: ItemDetail;
}

export default function FeaturePage({ item }: Props) {
  const itemsQuery = useQuery({
    queryKey: ['items'],
    queryFn: api.listItems,
    refetchInterval: 5000,
  });
  const stories = (itemsQuery.data ?? []).filter((i) => i.kind === 'story' && i.parentId === item.boardId);

  const boardCommentsQuery = useQuery({
    queryKey: ['board-comments', item.id],
    queryFn: () => api.getBoardComments(item.id),
    refetchInterval: 5000,
  });
  const boardComments = boardCommentsQuery.data ?? [];

  return (
    <Box>
      <PageHeader
        backTo={{ to: '/', label: 'Items' }}
        title={item.title}
        badges={<StatusBadge state={item.canonicalState} />}
        meta={<GateProgress state={item.canonicalState} />}
      />

      <Box mb="4">
        <Panel title="Stories">
          {stories.length === 0 ? (
            <EmptyState
              icon={<FileQuestion size={28} />}
              title="No story yet"
              hint="The PO agent drafts a story once grill questions are answered."
            />
          ) : (
            <Flex direction="column" gap="2">
              {stories.map((s) => (
                <Flex key={s.id} align="center" justify="between" gap="2">
                  <Link to={`/items/${s.id}`}>{s.title}</Link>
                  <StatusBadge state={s.canonicalState} />
                </Flex>
              ))}
            </Flex>
          )}
        </Panel>
      </Box>

      <Panel title="Board activity">
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
      </Panel>
    </Box>
  );
}
