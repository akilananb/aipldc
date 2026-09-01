import { useState } from 'react';
import { MapPin } from 'lucide-react';
import { Avatar, Badge, Box, Button, Card, Checkbox, Flex, Heading, IconButton, Select, Text, TextArea } from '@radix-ui/themes';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import Collapsible from '../components/Collapsible';
import type { Comment, CommentIntent } from '../types';
import type { AddCommentBody } from '../api';
import { intentBadgeColor } from '../ui-utils';

interface Props {
  comments: Comment[];
  draftTarget: string | null;
  onDraftTarget: (target: string | null) => void;
  onAddComment: (body: AddCommentBody) => void;
  submitting: boolean;
  onApproveAgentResult: (commentId: string) => void;
  approvingAgentResult: boolean;
  canApproveAgentResult: boolean;
}

const INTENTS: CommentIntent[] = ['change', 'question', 'note'];
const AGENT_RESULT_COLLAPSE_THRESHOLD = 600;

function groupByTarget(comments: Comment[]): { target: string; comments: Comment[] }[] {
  const map = new Map<string, Comment[]>();
  for (const c of comments) {
    const arr = map.get(c.target) ?? [];
    arr.push(c);
    map.set(c.target, arr);
  }
  return Array.from(map.entries()).map(([target, comments]) => ({ target, comments }));
}

export default function CommentPanel({
  comments,
  draftTarget,
  onDraftTarget,
  onAddComment,
  submitting,
  onApproveAgentResult,
  approvingAgentResult,
  canApproveAgentResult,
}: Props) {
  const [text, setText] = useState('');
  const [intent, setIntent] = useState<CommentIntent>('note');
  const [blocking, setBlocking] = useState(false);

  function submit() {
    if (!draftTarget || !text.trim()) return;
    onAddComment({ target: draftTarget, text: text.trim(), intent, blocking });
    setText('');
    setBlocking(false);
  }

  const grouped = groupByTarget(comments);

  return (
    <Box>
      <Heading size="3" mb="3">
        Comments
      </Heading>

      <Card size="2" mb="4">
        <Flex direction="column" gap="2">
          <Flex align="center" gap="2" wrap="wrap">
            <Text size="2" color="gray">
              Anchored to
            </Text>
            {draftTarget ? (
              <>
                <Badge color="indigo" variant="soft">
                  <MapPin size={12} />
                  {draftTarget}
                </Badge>
                <IconButton variant="ghost" size="1" aria-label="Clear anchor" onClick={() => onDraftTarget(null)}>
                  ×
                </IconButton>
              </>
            ) : (
              <Text size="2" color="gray">
                click a block in Preview to anchor a comment
              </Text>
            )}
          </Flex>

          <TextArea
            placeholder="Write a comment… (@analyst, @architect or @qa to request an agent analysis)"
            value={text}
            onChange={(e) => setText(e.target.value)}
            rows={3}
          />

          <Flex align="center" gap="3" wrap="wrap">
            <Select.Root value={intent} onValueChange={(v) => setIntent(v as CommentIntent)}>
              <Select.Trigger />
              <Select.Content>
                {INTENTS.map((i) => (
                  <Select.Item key={i} value={i}>
                    {i}
                  </Select.Item>
                ))}
              </Select.Content>
            </Select.Root>

            <Text as="label" size="2" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <Checkbox checked={blocking} onCheckedChange={(c) => setBlocking(c === true)} />
              Blocking
            </Text>

            <Button disabled={!draftTarget || !text.trim() || submitting} onClick={submit} loading={submitting}>
              Add comment
            </Button>
          </Flex>
        </Flex>
      </Card>

      <Flex direction="column" gap="3">
        {comments.length === 0 && (
          <Text size="2" color="gray">
            No comments on this version.
          </Text>
        )}
        {grouped.map((g) => (
          <Box key={g.target}>
            <Flex align="center" gap="2" mb="1">
              <Button variant="ghost" size="1" color="gray" onClick={() => onDraftTarget(g.target)}>
                {g.target}
              </Button>
            </Flex>
            <Flex direction="column" gap="2">
              {g.comments.map((c) => (
                <CommentCard
                  key={c.id}
                  comment={c}
                  onApproveAgentResult={onApproveAgentResult}
                  approvingAgentResult={approvingAgentResult}
                  canApproveAgentResult={canApproveAgentResult}
                />
              ))}
            </Flex>
          </Box>
        ))}
      </Flex>
    </Box>
  );
}

function AgentMarkdown({ content }: { content: string }) {
  const body = (
    <Box className="comment-md">
      <Markdown remarkPlugins={[remarkGfm]}>{content}</Markdown>
    </Box>
  );
  if (content.length > AGENT_RESULT_COLLAPSE_THRESHOLD) {
    return (
      <Collapsible maxHeight={240} defaultCollapsed>
        {body}
      </Collapsible>
    );
  }
  return body;
}

function CommentCard({
  comment,
  onApproveAgentResult,
  approvingAgentResult,
  canApproveAgentResult,
}: {
  comment: Comment;
  onApproveAgentResult: (commentId: string) => void;
  approvingAgentResult: boolean;
  canApproveAgentResult: boolean;
}) {
  const resolved = comment.resolvedInVersion != null;
  return (
    <Card size="2">
      <Flex justify="between" align="center" gap="2">
        <Flex align="center" gap="2" wrap="wrap">
          <Avatar size="1" radius="full" fallback={comment.by[0]?.toUpperCase() ?? '?'} />
          <Text size="2" weight="bold">
            {comment.by}
          </Text>
          <Badge color="gray" variant="soft">
            {comment.role}
          </Badge>
          <Badge color={intentBadgeColor(comment.intent)}>{comment.intent}</Badge>
          {comment.blocking && <Badge color="red">blocking</Badge>}
          {resolved && <Badge color="green">resolved v{comment.resolvedInVersion}</Badge>}
          {comment.drifted && <Badge color="amber">drifted</Badge>}
        </Flex>
        <Text size="1" color="gray">
          v{comment.version}
        </Text>
      </Flex>
      <Text size="2" mt="2" as="p">
        {comment.text}
      </Text>
      {comment.agentReply && (
        <Card variant="surface" mt="2">
          <Text size="1" color="gray" weight="bold" as="p">
            agent reply
          </Text>
          <Text size="2">{comment.agentReply}</Text>
        </Card>
      )}
      {comment.agentName && (
        <Card variant="surface" mt="2">
          {comment.agentResultStatus === 'running' && <Badge color="gray">@{comment.agentName} analyzing…</Badge>}
          {comment.agentResultStatus === 'failed' && (
            <Flex direction="column" gap="1">
              <Badge color="red">@{comment.agentName} failed</Badge>
              <Text size="1" color="gray" as="p">
                {comment.agentResultMd}
              </Text>
            </Flex>
          )}
          {comment.agentResultStatus === 'pending' && (
            <Flex direction="column" gap="2">
              <Badge color="amber">pending approval (PO/SquadLead)</Badge>
              <AgentMarkdown content={comment.agentResultMd ?? ''} />
              {canApproveAgentResult && (
                <Button size="1" loading={approvingAgentResult} onClick={() => onApproveAgentResult(comment.id)}>
                  Approve result
                </Button>
              )}
            </Flex>
          )}
          {comment.agentResultStatus === 'approved' && (
            <Flex direction="column" gap="2">
              <Badge color="green">
                @{comment.agentName} · approved by {comment.agentResultApprovedBy}
              </Badge>
              <AgentMarkdown content={comment.agentResultMd ?? ''} />
            </Flex>
          )}
        </Card>
      )}
    </Card>
  );
}
