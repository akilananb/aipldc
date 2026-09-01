import { useState } from 'react';
import { Badge, Button, Flex, Text, TextField, Tooltip } from '@radix-ui/themes';
import Panel from '../components/Panel';
import CopyHash from '../components/CopyHash';
import { useIdentity } from '../identity';
import { GATE_ROLES } from '../gates';
import type { ItemDetail } from '../types';
import type { ReviewActions } from './useReviewActions';
import GateProgress from './GateProgress';

interface Props {
  item: ItemDetail;
  actions: ReviewActions;
}

export default function GatePanel({ item, actions }: Props) {
  const identity = useIdentity();
  const [note, setNote] = useState('');
  const gate = item.gate;
  const approvals = Object.values(gate?.approvals ?? {});
  const stage = gate?.stage;

  const g2Allowed = GATE_ROLES.G2.includes(identity.role);
  const g2DisabledReason = !g2Allowed ? `Role ${identity.role} is not a Gate 2 checker` : null;

  return (
    <Panel>
      <Flex justify="between" align="center" gap="4" wrap="wrap" mb="3">
        <GateProgress state={item.canonicalState} />
        <CopyHash hash={item.latestContentHash} />
      </Flex>

      {(stage == null || stage === 'awaiting-G1') && (
        <Flex gap="3" align="center" wrap="wrap">
          <Tooltip content={actions.disabledReason ?? ''}>
            <Button
              disabled={actions.approveDisabled}
              onClick={() => actions.approve.mutate(note)}
              loading={actions.approve.isPending}
            >
              Approve
            </Button>
          </Tooltip>
          <Button
            color="amber"
            variant="soft"
            onClick={() => actions.requestChanges.mutate()}
            loading={actions.requestChanges.isPending}
          >
            Request changes
          </Button>
          <TextField.Root
            placeholder="Approval note (optional)"
            value={note}
            onChange={(e) => setNote(e.target.value)}
            style={{ width: 280 }}
          />
        </Flex>
      )}

      {stage === 'awaiting-G2' && (
        <Flex direction="column" gap="3">
          <Text size="2" weight="bold">
            Gate 2 · PR review
          </Text>
          <Flex gap="3" align="center" wrap="wrap">
            <Tooltip content={g2DisabledReason ?? ''}>
              <Button
                disabled={!g2Allowed}
                onClick={() => actions.prApprove.mutate('')}
                loading={actions.prApprove.isPending}
              >
                Approve PR
              </Button>
            </Tooltip>
            <Tooltip content={g2DisabledReason ?? ''}>
              <Button
                color="amber"
                variant="soft"
                disabled={!g2Allowed}
                onClick={() => actions.prRequestChanges.mutate()}
                loading={actions.prRequestChanges.isPending}
              >
                Request changes
              </Button>
            </Tooltip>
          </Flex>
          {approvals.length > 0 && (
            <Flex gap="2" wrap="wrap">
              {approvals.map((a) => (
                <Badge key={a.who} color="green">
                  {a.role} ✓
                </Badge>
              ))}
            </Flex>
          )}
        </Flex>
      )}

      {stage === 'awaiting-G3' && (
        <Text size="2" color="gray">
          Sign the release documents in the Release tab.
        </Text>
      )}

      {stage != null && !['awaiting-G1', 'awaiting-G2', 'awaiting-G3'].includes(stage) && approvals.length > 0 && (
        <Flex gap="2" wrap="wrap">
          {approvals.map((a) => (
            <Badge key={a.who} color="green">
              {a.role}: {a.who}
            </Badge>
          ))}
        </Flex>
      )}
    </Panel>
  );
}
