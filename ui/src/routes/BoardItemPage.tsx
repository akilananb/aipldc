import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Badge, Box } from '@radix-ui/themes';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';
import { api } from '../api';
import type { ItemDetail } from '../types';
import PageHeader from '../components/PageHeader';
import StatusBadge from '../components/StatusBadge';
import AgentActivityBadge from '../components/AgentActivityBadge';
import DemoSnapshotBadge from '../components/DemoSnapshotBadge';
import Panel from '../components/Panel';

interface Props {
  item: ItemDetail;
}

export default function BoardItemPage({ item }: Props) {
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
        backTo={{ to: '/', label: 'Items' }}
        title={item.title}
        badges={
          <>
            <Badge color="gray">{item.kind}</Badge>
            <StatusBadge state={item.canonicalState} />
            <AgentActivityBadge run={item.activeRun} />
            {item.snapshot && <DemoSnapshotBadge snapshot={item.snapshot} />}
          </>
        }
        meta={`board ${item.boardId} · profile ${item.profile}`}
      />

      <Panel title="Details">
        <Box className="review-md">
          <Markdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeHighlight]}>{item.description}</Markdown>
        </Box>
      </Panel>

      {parentStory && (
        <Box mt="4">
          <Panel title="Parent story">
            <Link to={`/items/${encodeURIComponent(parentStory.id)}`}>{parentStory.title}</Link>
          </Panel>
        </Box>
      )}
    </Box>
  );
}
