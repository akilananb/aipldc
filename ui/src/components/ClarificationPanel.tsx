import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Badge, Box, Button, Card, Flex, Text, TextArea } from '@radix-ui/themes';
import { api, errorMessage } from '../api';
import { useIdentity } from '../identity';
import { GATE_ROLES } from '../gates';
import type { GrillQuestion, ItemDetail } from '../types';
import Panel from './Panel';

interface Props {
  item: ItemDetail;
  idPrefix?: string;
  excludePrefix?: string;
}

export default function ClarificationPanel({ item, idPrefix, excludePrefix }: Props) {
  const identity = useIdentity();
  const queryClient = useQueryClient();
  const [drafts, setDrafts] = useState<Record<string, string>>({});

  const grillQuery = useQuery({
    queryKey: ['grill', item.id],
    queryFn: () => api.getGrill(item.id),
    refetchInterval: 2000,
    retry: false,
  });

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ['grill', item.id] });
    void queryClient.invalidateQueries({ queryKey: ['board-comments', item.id] });
    void queryClient.invalidateQueries({ queryKey: ['item', item.id] });
  }

  const answer = useMutation({
    mutationFn: ({ questionId, text }: { questionId: string; text: string }) => api.answerGrillQuestion(item.id, questionId, text),
    onSuccess: (_data, variables) => {
      invalidate();
      setDrafts((prev) => ({ ...prev, [variables.questionId]: '' }));
      toast.success('Answer submitted');
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  const park = useMutation({
    mutationFn: (questionId: string) => api.parkGrillQuestion(item.id, questionId),
    onSuccess: (_data, questionId) => {
      invalidate();
      setDrafts((prev) => ({ ...prev, [questionId]: '' }));
      toast.success('Question parked');
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  const questions = grillQuery.data?.questions
    .filter((q) => !idPrefix || q.id.startsWith(idPrefix))
    .filter((q) => !excludePrefix || !q.id.startsWith(excludePrefix));
  if (grillQuery.isError || !questions || questions.length === 0) {
    return null;
  }

  const anyReplyable = questions.some(
    (q) => GATE_ROLES.G1.includes(identity.role) || (q.id.startsWith('h') && GATE_ROLES.G2.includes(identity.role)),
  );
  const replyRolesHint = questions.some((q) => q.id.startsWith('h'))
    ? 'Switch to a Gate 1 or Gate 2 role to answer'
    : 'Switch to PO or SquadLead to answer';

  return (
    <Box mb="4">
      <Panel title={idPrefix === 'h' ? 'Build needs a decision' : 'Clarification questions'}>
        <Flex direction="column" gap="3">
          {questions.map((q) => (
            <QuestionCard
              key={q.id}
              question={q}
              draft={drafts[q.id] ?? ''}
              onDraftChange={(text) => setDrafts((prev) => ({ ...prev, [q.id]: text }))}
              canReply={GATE_ROLES.G1.includes(identity.role) || (q.id.startsWith('h') && GATE_ROLES.G2.includes(identity.role))}
              onAnswer={() => answer.mutate({ questionId: q.id, text: drafts[q.id] ?? '' })}
              onPark={() => park.mutate(q.id)}
              answering={answer.isPending && answer.variables?.questionId === q.id}
              parking={park.isPending && park.variables === q.id}
            />
          ))}
          {!anyReplyable && (
            <Text size="1" color="gray">
              {replyRolesHint}
            </Text>
          )}
        </Flex>
      </Panel>
    </Box>
  );
}

function QuestionCard({
  question,
  draft,
  onDraftChange,
  canReply,
  onAnswer,
  onPark,
  answering,
  parking,
}: {
  question: GrillQuestion;
  draft: string;
  onDraftChange: (text: string) => void;
  canReply: boolean;
  onAnswer: () => void;
  onPark: () => void;
  answering: boolean;
  parking: boolean;
}) {
  return (
    <Card size="2">
      <Flex align="center" gap="2" mb="1" wrap="wrap">
        <Badge variant="soft">{question.id}</Badge>
        {question.askedBy === 'po-agent' ? (
          <Badge color="violet">PO agent follow-up</Badge>
        ) : question.askedBy === 'build-agent' ? (
          <Badge color="amber">build loop</Badge>
        ) : (
          <Badge color="gray">grill</Badge>
        )}
        <Badge color="gray" variant="soft">
          {question.category}
        </Badge>
      </Flex>
      <Text size="2" as="p" mb="1">
        {question.question}
      </Text>
      {question.evidence && (
        <Text size="1" color="gray" as="p" mb="2">
          {question.evidence}
        </Text>
      )}
      {question.status === 'answered' && (
        <Text size="2" as="p">
          <Text weight="bold">{question.answeredBy}: </Text>
          {question.answer}
        </Text>
      )}
      {question.status === 'parked' && <Badge color="orange">parked</Badge>}
      {question.status === 'open' && (
        <Flex direction="column" gap="2">
          <TextArea
            value={draft}
            onChange={(e) => onDraftChange(e.target.value)}
            placeholder="Type your answer..."
            disabled={!canReply}
          />
          <Flex gap="2">
            <Button onClick={onAnswer} disabled={!canReply || draft.trim().length === 0 || answering}>
              Answer
            </Button>
            <Button variant="soft" onClick={onPark} disabled={!canReply || parking}>
              Park
            </Button>
          </Flex>
        </Flex>
      )}
    </Card>
  );
}
