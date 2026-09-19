import { useEffect, useState } from 'react';
import { Activity } from 'lucide-react';
import { useQuery } from '@tanstack/react-query';
import { Text } from '@radix-ui/themes';
import { api } from '../api';
import EmptyState from '../components/EmptyState';
import RunTimeline from './RunTimeline';

interface Props {
  id: string;
  snapshot?: boolean;
}

export default function ActivityTab({ id, snapshot = false }: Props) {
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
    enabled: !snapshot,
  });

  if (snapshot) {
    return (
      <Text size="2" color="gray">
        Snapshot evidence is recorded in review.md; no live agent run was started.
      </Text>
    );
  }

  if (activityQuery.isError || !activityQuery.data || activityQuery.data.length === 0) {
    return (
      <EmptyState
        icon={<Activity size={28} />}
        title="No agent activity yet"
        hint="Every agent run on this item shows up here as it happens."
      />
    );
  }

  return <RunTimeline runs={activityQuery.data} now={now} />;
}
