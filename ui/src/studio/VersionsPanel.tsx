import { useState } from 'react';
import ReactDiffViewer, { DiffMethod } from 'react-diff-viewer-continued';
import { Badge, Box, Button, Flex, Table, Text } from '@radix-ui/themes';
import { History } from 'lucide-react';
import { useAppearance } from '../theme';
import type { AgentVersion } from '../types';
import EmptyState from '../components/EmptyState';
import RelativeTime from '../components/RelativeTime';
import CopyHash from '../components/CopyHash';
import { versionText } from './draft';

interface Props {
  versions: AgentVersion[];
  currentVersion: number | null;
  draftText: string;
  canRollback: boolean;
  onRollback: (version: number) => void;
  rollingBack: boolean;
}

/** Published versions (immutable) with a side-by-side diff against the working draft or another version. */
export default function VersionsPanel({ versions, currentVersion, draftText, canRollback, onRollback, rollingBack }: Props) {
  const appearance = useAppearance();
  const newestFirst = [...versions].sort((a, b) => b.version - a.version);
  const [left, setLeft] = useState<number | null>(newestFirst[0]?.version ?? null);
  const [right, setRight] = useState<number | 'draft'>('draft');

  if (versions.length === 0) {
    return <EmptyState icon={<History size={28} />} title="No published versions" hint="Publish the draft to create v1." />;
  }
  const leftVersion = versions.find((v) => v.version === left);
  const rightText = right === 'draft' ? draftText : versionText(versions.find((v) => v.version === right)!);

  return (
    <Flex direction="column" gap="4">
      <Box style={{ overflowX: 'auto' }}>
        <Table.Root variant="surface" size="1">
          <Table.Header>
            <Table.Row>
              <Table.ColumnHeaderCell>Version</Table.ColumnHeaderCell>
              <Table.ColumnHeaderCell>Content hash</Table.ColumnHeaderCell>
              <Table.ColumnHeaderCell>Published</Table.ColumnHeaderCell>
              <Table.ColumnHeaderCell>Compare</Table.ColumnHeaderCell>
              <Table.ColumnHeaderCell />
            </Table.Row>
          </Table.Header>
          <Table.Body>
            {newestFirst.map((v) => (
              <Table.Row key={v.version}>
                <Table.RowHeaderCell>
                  <Flex gap="2" align="center">
                    <Text weight="bold">v{v.version}</Text>
                    {v.version === currentVersion && <Badge color="green">runs use this</Badge>}
                  </Flex>
                </Table.RowHeaderCell>
                <Table.Cell>
                  <CopyHash hash={v.contentHash.replace(/^sha256:/, '')} />
                </Table.Cell>
                <Table.Cell>
                  <RelativeTime iso={v.publishedAt} /> <Text size="1" color="gray">by {v.publishedBy}</Text>
                </Table.Cell>
                <Table.Cell>
                  <Flex gap="1">
                    <Button size="1" variant={left === v.version ? 'solid' : 'soft'} onClick={() => setLeft(v.version)} aria-pressed={left === v.version}>
                      Left
                    </Button>
                    <Button size="1" variant={right === v.version ? 'solid' : 'soft'} onClick={() => setRight(v.version)} aria-pressed={right === v.version}>
                      Right
                    </Button>
                  </Flex>
                </Table.Cell>
                <Table.Cell>
                  {canRollback && v.version !== currentVersion && (
                    <Button size="1" variant="soft" color="amber" loading={rollingBack} onClick={() => onRollback(v.version)}>
                      Use v{v.version} for new runs
                    </Button>
                  )}
                </Table.Cell>
              </Table.Row>
            ))}
          </Table.Body>
        </Table.Root>
      </Box>

      <Flex justify="between" align="center" wrap="wrap" gap="2">
        <Text size="2" weight="medium">
          Comparing v{leftVersion?.version} → {right === 'draft' ? 'working draft' : `v${right}`}
        </Text>
        <Button size="1" variant={right === 'draft' ? 'solid' : 'soft'} onClick={() => setRight('draft')} aria-pressed={right === 'draft'}>
          Right: working draft
        </Button>
      </Flex>
      <Box style={{ fontSize: 'var(--font-size-1)', overflowX: 'auto' }}>
        <ReactDiffViewer
          oldValue={leftVersion ? versionText(leftVersion) : ''}
          newValue={rightText}
          splitView
          showDiffOnly={false}
          useDarkTheme={appearance === 'dark'}
          compareMethod={DiffMethod.WORDS}
        />
      </Box>
    </Flex>
  );
}
