import { useState } from 'react';
import { Badge, Box, Button, Checkbox, Flex, Heading, Select, Text, TextArea } from '@radix-ui/themes';
import type { Comment, CommentIntent } from '../types';
import type { AddCommentBody } from '../api';
import { intentBadgeColor } from '../ui-utils';

interface Props {
  comments: Comment[];
  draftTarget: string | null;
  onDraftTarget: (target: string | null) => void;
  onAddComment: (body: AddCommentBody) => void;
  submitting: boolean;
}

const INTENTS: CommentIntent[] = ['change', 'question', 'note'];

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

      <Box
        mb="4"
        style={{ border: '1px solid var(--gray-a5)', borderRadius: 8, padding: 12 }}
      >
        <Flex direction="column" gap="2">
          <Flex align="center" gap="2" wrap="wrap">
            <Text size="2" color="gray">
              Anchored to
            </Text>
            {draftTarget ? (
              <>
                <Badge color="indigo">{draftTarget}</Badge>
                <Button variant="ghost" size="1" onClick={() => onDraftTarget(null)}>
                  clear
                </Button>
              </>
            ) : (
              <Text size="2" color="gray">
                click a block in Preview to anchor a comment
              </Text>
            )}
          </Flex>

          <TextArea
            placeholder="Write a comment…"
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

            <label style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <Checkbox checked={blocking} onCheckedChange={(c) => setBlocking(c === true)} />
              <Text size="2">Blocking</Text>
            </label>

            <Button disabled={!draftTarget || !text.trim() || submitting} onClick={submit} loading={submitting}>
              Add comment
            </Button>
          </Flex>
        </Flex>
      </Box>

      <Flex direction="column" gap="3">
        {comments.length === 0 && (
          <Text size="2" color="gray">
            No comments on this version.
          </Text>
        )}
        {grouped.map((g) => (
          <Box key={g.target}>
            <Flex align="center" gap="2" mb="1">
              <Badge
                color="gray"
                variant="soft"
                style={{ cursor: 'pointer' }}
                onClick={() => onDraftTarget(g.target)}
              >
                {g.target}
              </Badge>
            </Flex>
            <Flex direction="column" gap="2">
              {g.comments.map((c) => (
                <CommentCard key={c.id} comment={c} />
              ))}
            </Flex>
          </Box>
        ))}
      </Flex>
    </Box>
  );
}

function CommentCard({ comment }: { comment: Comment }) {
  const resolved = comment.resolvedInVersion != null;
  return (
    <Box style={{ border: '1px solid var(--gray-a5)', borderRadius: 8, padding: 10 }}>
      <Flex justify="between" align="center" gap="2">
        <Flex align="center" gap="2" wrap="wrap">
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
      <Text size="2" mt="2">
        {comment.text}
      </Text>
      {comment.agentReply && (
        <Box mt="2" style={{ background: 'var(--gray-a3)', borderRadius: 6, padding: 8 }}>
          <Text size="1" color="gray" weight="bold">
            agent reply
          </Text>
          <Text size="2">{comment.agentReply}</Text>
        </Box>
      )}
    </Box>
  );
}
