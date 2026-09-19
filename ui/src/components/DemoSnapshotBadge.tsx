import { Badge, Flex } from '@radix-ui/themes';
import type { DemoSnapshot } from '../types';

interface Props {
  snapshot: DemoSnapshot;
  /** Suppresses the "Demo snapshot: <label>" chip - use inside a stage group whose header
   * already states the label, to avoid reprinting it on every row. */
  compact?: boolean;
}

export default function DemoSnapshotBadge({ snapshot, compact }: Props) {
  return (
    <Flex gap="1" align="center" wrap="wrap">
      {!compact && (
        <Badge color="indigo" variant="soft">
          Demo snapshot: {snapshot.label}
        </Badge>
      )}
      <Badge color="gray" variant="soft">
        Read-only
      </Badge>
      {snapshot.replay && (
        <Badge color="amber" variant="soft">
          Replay evidence
        </Badge>
      )}
    </Flex>
  );
}
