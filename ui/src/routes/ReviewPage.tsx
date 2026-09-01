import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Badge,
  Box,
  Button,
  Callout,
  Flex,
  Heading,
  Select,
  Tabs,
  Text,
  TextField,
} from '@radix-ui/themes';
import { api } from '../api';
import { useIdentity } from '../identity';
import type { CommentIntent, GateState } from '../types';
import { stateBadgeColor } from '../ui-utils';
import CommentPanel from '../review/CommentPanel';
import PreviewTab from '../review/PreviewTab';
import SourceTab from '../review/SourceTab';
import DiffTab from '../review/DiffTab';
import ReviewMdTab from '../review/ReviewMdTab';

// Pilot G1 roles. Known simplification: hard-coded client-side to match
// infra/pdlc.yaml's gates.G1.roles (the REST contract does not expose the
// role list).
const G1_ROLES = ['PO', 'SquadLead'];

type Tab = 'preview' | 'source' | 'diff' | 'reviewmd';

function GateBadge({ gate }: { gate: GateState | null }) {
  if (!gate) {
    return (
      <Badge color="gray" variant="soft">
        no gate
      </Badge>
    );
  }
  const approvals = Object.values(gate.approvals);
  const hasPo = approvals.some((a) => a.role === 'PO');
  const hasLead = approvals.some((a) => a.role === 'SquadLead');
  if (hasPo && hasLead) {
    return <Badge color="green">G1 passed</Badge>;
  }
  return (
    <Badge color="amber">
      awaiting G1 ({approvals.length}/{G1_ROLES.length} approvals)
    </Badge>
  );
}

export default function ReviewPage() {
  const { id } = useParams<{ id: string }>();
  const identity = useIdentity();
  const queryClient = useQueryClient();

  const [selectedVersion, setSelectedVersion] = useState<number | null>(null);
  const [tab, setTab] = useState<Tab>('preview');
  const [draftTarget, setDraftTarget] = useState<string | null>(null);
  const [note, setNote] = useState('');

  const itemQuery = useQuery({
    queryKey: ['item', id],
    queryFn: () => api.getItem(id!),
    enabled: !!id,
    refetchInterval: 2000,
  });

  const item = itemQuery.data;
  const latestVersion = item?.latestVersion ?? 0;

  // Keep the selected version pinned to the latest once we know it.
  useEffect(() => {
    if (latestVersion <= 0) return;
    if (selectedVersion === null) {
      setSelectedVersion(latestVersion);
    } else if (selectedVersion > latestVersion) {
      setSelectedVersion(latestVersion);
    }
  }, [latestVersion, selectedVersion]);

  const artifactQuery = useQuery({
    queryKey: ['artifact', id, selectedVersion],
    queryFn: () => api.getArtifact(id!, selectedVersion!),
    enabled: !!id && selectedVersion != null,
    refetchInterval: 2000,
  });

  const prevArtifactQuery = useQuery({
    queryKey: ['artifact', id, (selectedVersion ?? 0) - 1],
    queryFn: () => api.getArtifact(id!, (selectedVersion ?? 0) - 1),
    enabled: !!id && selectedVersion != null && selectedVersion > 1,
  });

  const reviewMdQuery = useQuery({
    queryKey: ['reviewmd', id],
    queryFn: () => api.getReviewMd(id!),
    enabled: !!id && tab === 'reviewmd',
    refetchInterval: 2000,
  });

  const addCommentMutation = useMutation({
    mutationFn: (body: { target: string; text: string; intent: CommentIntent; blocking: boolean }) =>
      api.addComment(id!, body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['artifact', id] });
      void queryClient.invalidateQueries({ queryKey: ['item', id] });
    },
  });

  const approveMutation = useMutation({
    mutationFn: (n: string) => api.approve(id!, n),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['item', id] });
      void queryClient.invalidateQueries({ queryKey: ['artifact', id] });
    },
  });

  const requestChangesMutation = useMutation({
    mutationFn: () => api.requestChanges(id!),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['item', id] });
      void queryClient.invalidateQueries({ queryKey: ['artifact', id] });
    },
  });

  const currentComments = artifactQuery.data?.comments ?? [];
  const hasOpenBlocking = currentComments.some((c) => c.blocking && c.resolvedInVersion == null);

  const gate = item?.gate ?? null;
  const currentApprovals = gate?.approvals ?? {};
  const roleAllowed = G1_ROLES.includes(identity.role);
  const alreadyApproved = Object.values(currentApprovals).some((a) => a.who === identity.user);
  const approveDisabled = hasOpenBlocking || !roleAllowed || alreadyApproved;

  const versions = useMemo(() => {
    if (latestVersion <= 0) return [];
    return Array.from({ length: latestVersion }, (_, i) => i + 1);
  }, [latestVersion]);

  if (itemQuery.isLoading) return <Text color="gray">Loading item…</Text>;
  if (itemQuery.isError) {
    return (
      <Callout.Root color="red" style={{ maxWidth: 720 }}>
        <Callout.Text>Failed to load item: {String(itemQuery.error)}</Callout.Text>
      </Callout.Root>
    );
  }
  if (!item) return null;

  return (
    <Box>
      <Flex justify="between" align="start" gap="4" mb="4" wrap="wrap">
        <Box>
          <Flex align="center" gap="3">
            <Link to="/">← Items</Link>
            <Heading size="4">{item.title}</Heading>
          </Flex>
          <Flex gap="2" mt="2" align="center" wrap="wrap">
            <Badge color="gray">{item.kind}</Badge>
            <Badge color={stateBadgeColor(item.canonicalState)}>{item.canonicalState}</Badge>
            <GateBadge gate={gate} />
            <Text size="2" color="gray">
              board {item.boardId} · profile {item.profile}
            </Text>
          </Flex>
        </Box>

        <Flex direction="column" gap="2" align="end">
          <Text size="2" color="gray">
            Version
          </Text>
          <Select.Root
            value={selectedVersion != null ? String(selectedVersion) : undefined}
            onValueChange={(v) => setSelectedVersion(Number(v))}
          >
            <Select.Trigger style={{ minWidth: 110 }} />
            <Select.Content>
              {versions.map((v) => (
                <Select.Item key={v} value={String(v)}>
                  v{v}
                </Select.Item>
              ))}
            </Select.Content>
          </Select.Root>
        </Flex>
      </Flex>

      <Flex gap="3" align="center" mb="3" wrap="wrap">
        <Button disabled={approveDisabled} onClick={() => approveMutation.mutate(note)} loading={approveMutation.isPending}>
          Approve
        </Button>
        <Button color="amber" variant="soft" onClick={() => requestChangesMutation.mutate()} loading={requestChangesMutation.isPending}>
          Request changes
        </Button>
        <TextField.Root
          placeholder="Approval note (optional)"
          value={note}
          onChange={(e) => setNote(e.target.value)}
          style={{ width: 280 }}
        />
        <Text size="2" color="gray">
          signing sha256:{item.latestContentHash}
        </Text>
      </Flex>

      <Flex gap="2" mb="3" wrap="wrap">
        {hasOpenBlocking && <Badge color="red">open blocking comment</Badge>}
        {!roleAllowed && (
          <Badge color="gray" variant="soft">
            role {identity.role} cannot approve G1
          </Badge>
        )}
        {alreadyApproved && <Badge color="green">you already approved</Badge>}
      </Flex>

      {approveMutation.isError && (
        <Callout.Root color="red" mb="3" style={{ maxWidth: 720 }}>
          <Callout.Text>Approve failed: {String(approveMutation.error)}</Callout.Text>
        </Callout.Root>
      )}
      {requestChangesMutation.isError && (
        <Callout.Root color="red" mb="3" style={{ maxWidth: 720 }}>
          <Callout.Text>Request changes failed: {String(requestChangesMutation.error)}</Callout.Text>
        </Callout.Root>
      )}

      <Tabs.Root value={tab} onValueChange={(v) => setTab(v as Tab)}>
        <Tabs.List>
          <Tabs.Trigger value="preview">Preview</Tabs.Trigger>
          <Tabs.Trigger value="source">Source</Tabs.Trigger>
          <Tabs.Trigger value="diff" disabled={selectedVersion == null || selectedVersion <= 1}>
            Diff
          </Tabs.Trigger>
          <Tabs.Trigger value="reviewmd">review.md</Tabs.Trigger>
        </Tabs.List>

        <Box pt="4">
          <Tabs.Content value="preview">
            {artifactQuery.isLoading && <Text color="gray">Loading version…</Text>}
            {artifactQuery.isError && (
              <Callout.Root color="red" style={{ maxWidth: 720 }}>
                <Callout.Text>Failed to load artifact: {String(artifactQuery.error)}</Callout.Text>
              </Callout.Root>
            )}
            {artifactQuery.data && (
              <Flex gap="4" align="start">
                <Box flexGrow="1" style={{ minWidth: 0 }}>
                  <PreviewTab
                    markdown={artifactQuery.data.storyMarkdown}
                    onLineSelect={(line) => setDraftTarget(`line:${line}`)}
                  />
                </Box>
                <Box style={{ width: 380, flexShrink: 0 }}>
                  <CommentPanel
                    comments={currentComments}
                    draftTarget={draftTarget}
                    onDraftTarget={setDraftTarget}
                    onAddComment={addCommentMutation.mutate}
                    submitting={addCommentMutation.isPending}
                  />
                </Box>
              </Flex>
            )}
          </Tabs.Content>

          <Tabs.Content value="source">
            {artifactQuery.data && (
              <SourceTab markdown={artifactQuery.data.storyMarkdown} comments={currentComments} />
            )}
          </Tabs.Content>

          <Tabs.Content value="diff">
            {selectedVersion != null && selectedVersion > 1 && (
              <DiffTab
                oldMarkdown={prevArtifactQuery.data?.storyMarkdown ?? ''}
                newMarkdown={artifactQuery.data?.storyMarkdown ?? ''}
                oldLoading={prevArtifactQuery.isLoading}
                oldError={prevArtifactQuery.isError}
              />
            )}
          </Tabs.Content>

          <Tabs.Content value="reviewmd">
            <ReviewMdTab
              loading={reviewMdQuery.isLoading}
              error={reviewMdQuery.isError ? String(reviewMdQuery.error) : null}
              content={reviewMdQuery.data ?? null}
            />
          </Tabs.Content>
        </Box>
      </Tabs.Root>
    </Box>
  );
}
