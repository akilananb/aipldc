import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Box, Tabs } from '@radix-ui/themes';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';
import { api } from '../api';
import type { ItemDetail } from '../types';
import PageHeader, { MetaItems } from '../components/PageHeader';
import StatusBadge from '../components/StatusBadge';
import AgentActivityBadge from '../components/AgentActivityBadge';
import DemoSnapshotBadge from '../components/DemoSnapshotBadge';
import Surface from '../components/Surface';

interface Props {
  item: ItemDetail;
}

export default function BoardItemPage({ item }: Props) {
  const [tab, setTab] = useState('details');

  const itemsQuery = useQuery({
    queryKey: ['items'],
    queryFn: api.listItems,
    refetchInterval: 5000,
  });
  const parentStory = item.parentId
    ? (itemsQuery.data ?? []).find((i) => i.kind === 'story' && i.boardId === item.parentId)
    : undefined;

  return (
    <Box>
      <PageHeader
        title={item.title}
        badges={
          <>
            <span className="pill">{item.kind.toUpperCase()}</span>
            <StatusBadge state={item.canonicalState} />
            <AgentActivityBadge run={item.activeRun} />
            {item.snapshot && <DemoSnapshotBadge snapshot={item.snapshot} />}
          </>
        }
        meta={<MetaItems items={[`board ${item.boardId}`, `profile ${item.profile}`]} />}
        subtitle={
          parentStory ? (
            <>
              Parent story: <Link to={`/items/${encodeURIComponent(parentStory.id)}`}>{parentStory.title}</Link>
            </>
          ) : undefined
        }
      />

      <Surface value={tab} onValueChange={setTab} tabs={[{ value: 'details', label: 'Details' }]}>
        <Tabs.Content value="details">
          <Box className="review-md">
            <Markdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeHighlight]}>{item.description}</Markdown>
          </Box>
        </Tabs.Content>
      </Surface>
    </Box>
  );
}
