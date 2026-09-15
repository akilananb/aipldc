import { Badge, Spinner, Tooltip } from '@radix-ui/themes';
import type { AgentRun } from '../types';
import { agentRunLabel } from '../ui-utils';

interface Props {
  run: AgentRun | null;
}

export default function AgentActivityBadge({ run }: Props) {
  if (!run) return null;
  return (
    <Tooltip content={`started ${new Date(run.startedAt).toLocaleTimeString()}`}>
      <Badge color="blue" variant="soft">
        <Spinner size="1" /> {agentRunLabel(run)}
      </Badge>
    </Tooltip>
  );
}
