import { useEffect, useState } from 'react';
import { Activity, CircleCheck, CircleDashed, CircleX } from 'lucide-react';
import { useQuery } from '@tanstack/react-query';
import { Badge, Card, Flex, Link, Spinner, Text } from '@radix-ui/themes';
import { api } from '../api';
import EmptyState from '../components/EmptyState';
import RelativeTime from '../components/RelativeTime';
import { agentRunLabel, runOutcomeColor } from '../ui-utils';
import type { AgentRun } from '../types';

interface Props {
  id: string;
}

function formatDuration(startedAt: string, endIso: string | null): string {
  if (!endIso) return '';
  const startMs = new Date(startedAt).getTime();
  const endMs = new Date(endIso).getTime();
  if (Number.isNaN(startMs) || Number.isNaN(endMs)) return '';
  const totalSec = Math.max(0, Math.round((endMs - startMs) / 1000));
  if (totalSec < 60) return `${totalSec}s`;
  const min = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  return `${min}m ${sec}s`;
}

function statusIcon(run: AgentRun) {
  if (run.status === 'running') return <Spinner size="1" />;
  if (run.status === 'abandoned') return <CircleDashed size={16} color="var(--gray-9)" />;
  if (run.outcome === 'ok' || run.outcome === 'green') return <CircleCheck size={16} color="var(--green-9)" />;
  if (run.outcome === 'error' || run.outcome === 'red') return <CircleX size={16} color="var(--red-9)" />;
  return <CircleDashed size={16} color="var(--gray-9)" />;
}

export default function ActivityTab({ id }: Props) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  const activityQuery = useQuery({
    queryKey: ['activity', id],
    queryFn: () => api.getActivity(id),
    refetchInterval: 2000,
    retry: false,
  });

  if (activityQuery.isError || !activityQuery.data || activityQuery.data.length === 0) {
    return (
      <EmptyState
        icon={<Activity size={28} />}
        title="No agent activity yet"
        hint="Every agent run on this item shows up here as it happens."
      />
    );
  }

  return (
    <Flex direction="column" gap="2">
      {activityQuery.data.map((run) => {
        const end = run.finishedAt ?? (run.status === 'running' ? new Date(now).toISOString() : null);
        const duration = formatDuration(run.startedAt, end);
        return (
          <Card key={run.id} size="1">
            <Flex align="center" gap="2" wrap="wrap">
              {statusIcon(run)}
              <Text size="2" weight="bold">
                {agentRunLabel(run)}
              </Text>
              <Badge
                color={run.status === 'running' ? 'blue' : run.status === 'abandoned' ? 'gray' : runOutcomeColor(run.outcome)}
                variant="soft"
              >
                {run.status === 'finished' ? (run.outcome ?? 'finished') : run.status}
              </Badge>
              <RelativeTime iso={run.startedAt} />
              {duration && (
                <Text size="1" color="gray">
                  {duration}
                </Text>
              )}
              {run.traceUrl && (
                <Link href={run.traceUrl} target="_blank" rel="noreferrer" size="1">
                  trace
                </Link>
              )}
            </Flex>
          </Card>
        );
      })}
    </Flex>
  );
}
