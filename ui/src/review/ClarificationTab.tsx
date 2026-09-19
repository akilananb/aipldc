import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { FileQuestion } from 'lucide-react';
import { Avatar, Button, Spinner, Text, TextArea } from '@radix-ui/themes';
import { api, errorMessage } from '../api';
import {
  GRILL_ASSUMPTION_EVIDENCE,
  GRILL_CONFIRMATION_EVIDENCE,
  grillCategoryLabel,
  grillConfirmationSummary,
  splitGrillRecommendation,
} from '../ui-utils';
import EmptyState from '../components/EmptyState';
import ViewHead from '../components/ViewHead';
import type { ClarificationScope } from './useClarification';
import { useClarification } from './useClarification';
import type { GrillQuestion, ItemDetail } from '../types';

interface Props {
  item: ItemDetail;
  scope: ClarificationScope;
}

interface Draft {
  text: string;
  park: boolean;
}
export default function ClarificationTab({ item, scope }: Props) {
  const queryClient = useQueryClient();
  const clarification = useClarification(item, scope);
  const { query, questions, open, confirmation, confirmedConfirmation, answered, parked, coverage, rounds, resolved, canReply, readOnly, grillRunning, replyRolesHint } = clarification;

  const [drafts, setDrafts] = useState<Record<string, Draft>>({});
  const [confirmDraft, setConfirmDraft] = useState('');

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ['grill', item.id] });
    void queryClient.invalidateQueries({ queryKey: ['board-comments', item.id] });
    void queryClient.invalidateQueries({ queryKey: ['item', item.id] });
  }

  const submitBatch = useMutation({
    mutationFn: async () => {
      for (const q of open) {
        const d = drafts[q.id];
        if (!d) continue;
        if (d.park) {
          await api.parkGrillQuestion(item.id, q.id);
        } else if (d.text.trim()) {
          await api.answerGrillQuestion(item.id, q.id, d.text.trim());
        } else {
          continue;
        }
        setDrafts((prev) => {
          const next = { ...prev };
          delete next[q.id];
          return next;
        });
      }
    },
    onSuccess: () => {
      invalidate();
      toast.success('Batch submitted — the grill agent folds it into the next round');
    },
    onError: (e) => {
      invalidate();
      toast.error(errorMessage(e));
    },
  });

  const confirm = useMutation({
    mutationFn: (text: string) => api.answerGrillQuestion(item.id, confirmation!.id, text),
    onSuccess: () => {
      invalidate();
      setConfirmDraft('');
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  if (query.isError) {
    return (
      <>
        <ViewHead title="Clarification interview" />
        <EmptyState
          icon={<FileQuestion size={28} />}
          title="No clarification interview"
          hint="Questions appear here once the grill agent posts its first round."
        />
      </>
    );
  }
  if (query.isLoading) {
    return (
      <>
        <ViewHead title="Clarification interview" />
        <Text color="gray">Loading interview…</Text>
      </>
    );
  }

  const draftedAnswers = open.filter((q) => drafts[q.id] && !drafts[q.id].park && drafts[q.id].text.trim()).length;
  const draftedParks = open.filter((q) => drafts[q.id]?.park).length;
  const drafted = draftedAnswers + draftedParks;

  const categories = Array.from(new Set(open.map((q) => q.category))).map(grillCategoryLabel);

  return (
    <>
      <ViewHead
        title="Clarification interview"
        sub={
          scope === 'intake'
            ? 'Answer or park every open question; the grill agent then folds the batch and posts the next round.'
            : 'Build-loop questions the coding agent escalated to a human; intake decisions are listed as history.'
        }
        aside={
          <span className={`pill ${resolved ? 'pass' : grillRunning ? 'info' : 'review'}`}>
            <i className={`dot${grillRunning ? ' pulse' : ''}`} /> {resolved ? 'Resolved' : grillRunning ? 'Grill agent thinking' : 'Interview active'}
          </span>
        }
      />

      {questions.length > 0 && (
        <div className="agent-note">
          <Avatar size="2" radius="large" fallback="G" />
          <div>
            <strong>Grill agent · Round {rounds}</strong>
            <p>
              {open.length} open question{open.length === 1 ? '' : 's'} across {coverage.filter((c) => c.total > 0).length} categor
              {coverage.filter((c) => c.total > 0).length === 1 ? 'y' : 'ies'}; {answered.length} answered, {parked.length} parked.
            </p>
            <div className="chips">
              <span className="chip">{open.length} open</span>
              <span className="chip">{answered.length} answered</span>
              <span className="chip">{parked.length} parked</span>
            </div>
            {grillRunning && (
              <p className="agent-note-running">
                <Spinner size="1" /> Generating the next round — your answers are recorded.
                {item.activeRun?.traceUrl && (
                  <>
                    {' '}
                    <a href={item.activeRun.traceUrl} target="_blank" rel="noreferrer">
                      View trace
                    </a>
                  </>
                )}
              </p>
            )}
          </div>
        </div>
      )}

      {confirmation && (
        <section className="batch confirm">
          <div className="batch-head">
            <div>
              <span className="batch-kicker">Final check</span>
              <h3>Confirm shared understanding</h3>
              <p>Reply confirm to proceed to story drafting, or describe corrections; parking does not approve intake.</p>
            </div>
          </div>
          <p className="confirm-summary">{grillConfirmationSummary(confirmation.question)}</p>
          <TextArea
            value={confirmDraft}
            onChange={(e) => setConfirmDraft(e.target.value)}
            placeholder="Describe corrections…"
            disabled={!canReply || confirm.isPending}
          />
          <div className="q-actions">
            <Button onClick={() => confirm.mutate('confirm')} disabled={!canReply || confirm.isPending}>
              Confirm
            </Button>
            <Button
              variant="soft"
              onClick={() => confirm.mutate(confirmDraft.trim())}
              disabled={!canReply || !confirmDraft.trim() || confirm.isPending}
            >
              Send corrections
            </Button>
          </div>
        </section>
      )}

      {open.length > 0 && (
        <section className="batch">
          <div className="batch-head">
            <div>
              <span className="batch-kicker">
                Round {rounds}
                {categories.length > 0 ? ` · ${categories.join(' · ')}` : ''}
              </span>
              <h3>Resolve the open questions</h3>
              <p>Pick the recommended answer, write your own, or park the question.</p>
            </div>
            <span className="pill">
              {drafted} of {open.length} drafted
            </span>
          </div>
          {open.map((q, i) => (
            <QuestionCard
              key={q.id}
              index={i + 1}
              question={q}
              draft={drafts[q.id] ?? { text: '', park: false }}
              onChange={(d) => setDrafts((prev) => ({ ...prev, [q.id]: d }))}
              disabled={!canReply || submitBatch.isPending}
            />
          ))}
        </section>
      )}

      {open.length === 0 && !confirmation && questions.length > 0 && (
        <section className="batch">
          <div className="batch-head">
            <div>
              <span className="batch-kicker">Round {rounds}</span>
              <h3>{resolved ? 'Interview resolved' : 'Waiting on the grill agent'}</h3>
              <p>{resolved ? 'Every question is answered or parked.' : 'Waiting for the grill agent to post the next round.'}</p>
            </div>
          </div>
        </section>
      )}

      {questions.length === 0 && (
        <EmptyState
          icon={<FileQuestion size={28} />}
          title="No clarification interview"
          hint="Questions appear here once the grill agent posts its first round."
        />
      )}

      {answered.length + parked.length + (confirmedConfirmation ? 1 : 0) > 0 && (
        <section className="batch history">
          <div className="batch-head">
            <div>
              <span className="batch-kicker">History</span>
              <h3>Resolved questions</h3>
            </div>
          </div>
          {[...answered, ...(confirmedConfirmation ? [confirmedConfirmation] : []), ...parked].map((q) => (
            <article key={q.id} className="q-card resolved">
              <span className="q-num">{q.id}</span>
              <div className="q-body">
                <header>
                  <h4>{q.evidence === GRILL_CONFIRMATION_EVIDENCE ? grillConfirmationSummary(q.question) : splitGrillRecommendation(q.question).body}</h4>
                  <span className="q-status">{q.status === 'parked' ? 'Parked' : `answered by ${q.answeredBy}`}</span>
                </header>
                {q.status === 'answered' && <p className="q-answer">{q.answer}</p>}
              </div>
            </article>
          ))}
        </section>
      )}

      {open.length > 0 && (
        <div className="batch-bar">
          <div>
            <strong>
              {draftedAnswers} answered · {draftedParks} parked
            </strong>
            <small>
              {readOnly
                ? 'Read-only demo snapshot.'
                : !canReply
                  ? replyRolesHint
                  : 'Unanswered questions stay open; the next round waits until every one is answered or parked.'}
            </small>
          </div>
          <div className="q-actions">
            <Button variant="soft" disabled={drafted === 0 || submitBatch.isPending} onClick={() => setDrafts({})}>
              Clear drafts
            </Button>
            <Button disabled={!canReply || drafted === 0 || submitBatch.isPending} onClick={() => submitBatch.mutate()}>
              {submitBatch.isPending ? 'Submitting…' : `Submit batch (${drafted})`}
            </Button>
          </div>
        </div>
      )}
    </>
  );
}

function QuestionCard({
  index,
  question,
  draft,
  onChange,
  disabled,
}: {
  index: number;
  question: GrillQuestion;
  draft: Draft;
  onChange: (draft: Draft) => void;
  disabled: boolean;
}) {
  const { body, recommendation } = splitGrillRecommendation(question.question);
  const isRec = recommendation != null && !draft.park && draft.text === recommendation;

  return (
    <article className={`q-card${draft.park ? ' parked' : draft.text.trim() ? ' drafted' : ''}`}>
      <span className="q-num">{String(index).padStart(2, '0')}</span>
      <div className="q-body">
        <header>
          <h4>{body}</h4>
          <span className="q-status">{draft.park ? 'Parking' : draft.text.trim() ? 'Drafted' : 'Open'}</span>
        </header>
        <p className="q-evidence">
          {question.evidence === GRILL_ASSUMPTION_EVIDENCE ? 'Assumption check — no concrete evidence cited.' : question.evidence}
        </p>
        <div className="chips">
          <span className="chip">{question.id}</span>
          <span className="chip">{grillCategoryLabel(question.category)}</span>
          {question.askedBy === 'po-agent' && <span className="chip violet">PO agent follow-up</span>}
          {question.askedBy === 'build-agent' && <span className="chip amber">Build loop</span>}
        </div>
        {recommendation && (
          <button
            type="button"
            className={`opt-chip${isRec ? ' selected' : ''}`}
            disabled={disabled}
            onClick={() => onChange({ text: isRec ? '' : recommendation, park: false })}
          >
            <strong>{recommendation}</strong>
            <small>Recommended</small>
          </button>
        )}
        <div className="q-input">
          <TextArea
            size="1"
            value={draft.park ? '' : draft.text}
            onChange={(e) => onChange({ text: e.target.value, park: false })}
            placeholder={recommendation ? 'Add context or a different answer…' : 'Type your answer…'}
            disabled={disabled || draft.park}
          />
          <Button variant={draft.park ? 'solid' : 'soft'} color={draft.park ? 'gray' : undefined} disabled={disabled} onClick={() => onChange({ text: '', park: !draft.park })}>
            {draft.park ? 'Parked' : 'Park'}
          </Button>
        </div>
      </div>
    </article>
  );
}
