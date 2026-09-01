import { Flex, Text, Tooltip } from '@radix-ui/themes';
import StatusBadge from '../components/StatusBadge';

interface Props {
  state: string;
}

const STAGES: { stage: string; label: string }[] = [
  { stage: 'needs-clarification', label: 'Intake' },
  { stage: 'ready-for-story', label: 'Ready' },
  { stage: 'awaiting-G1', label: 'Gate 1' },
  { stage: 'approved', label: 'Approved' },
  { stage: 'planned', label: 'Planned' },
  { stage: 'in-progress', label: 'Building' },
  { stage: 'awaiting-G2', label: 'Gate 2' },
  { stage: 'awaiting-G3', label: 'Gate 3' },
  { stage: 'done', label: 'Done' },
];

export default function GateProgress({ state }: Props) {
  const index = STAGES.findIndex((s) => s.stage === state);
  if (index === -1) {
    return <StatusBadge state={state} />;
  }

  return (
    <Flex align="center" gap="2" wrap="wrap">
      {STAGES.map((s, i) => {
        const status = i < index ? 'done' : i === index ? 'current' : 'future';
        return (
          <Tooltip key={s.stage} content={s.label}>
            <Flex align="center" gap="1">
              <span
                style={{
                  width: 9,
                  height: 9,
                  borderRadius: '50%',
                  background: status === 'done' ? 'var(--accent-9)' : status === 'current' ? 'transparent' : 'var(--gray-a5)',
                  boxShadow: status === 'current' ? '0 0 0 2px var(--accent-9)' : undefined,
                  display: 'inline-block',
                }}
              />
              <Text size="1" color={status === 'future' ? 'gray' : undefined} weight={status === 'current' ? 'bold' : undefined}>
                {s.label}
              </Text>
              {i < STAGES.length - 1 && (
                <span style={{ width: 14, height: 1, background: 'var(--gray-a5)', display: 'inline-block' }} />
              )}
            </Flex>
          </Tooltip>
        );
      })}
    </Flex>
  );
}
