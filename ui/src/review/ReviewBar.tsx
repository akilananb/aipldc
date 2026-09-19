import { useState } from 'react';
import { Button, TextField, Tooltip } from '@radix-ui/themes';
import { Check, Clock, GitPullRequest, Rocket, ShieldCheck } from 'lucide-react';
import { useIdentity } from '../identity';
import { GATE_ROLES } from '../gates';
import type { ItemDetail } from '../types';
import type { ReviewActions } from './useReviewActions';
import { stageSubtitle } from './pipeline';

interface Props {
  item: ItemDetail;
  actions: ReviewActions;
  onOpenRelease?: () => void;
}

export default function ReviewBar({ item, actions, onOpenRelease }: Props) {
  const identity = useIdentity();
  const [note, setNote] = useState('');
  const gate = item.gate;
  const approvals = Object.values(gate?.approvals ?? {});
  const stage = gate?.stage;
  const qualityBlocked = item.qualityVerdict === 'failed';
  const readOnly = item.snapshot != null;

  const g2Allowed = GATE_ROLES.G2.includes(identity.role);
  const g2DisabledReason = !g2Allowed ? `Role ${identity.role} is not a Gate 2 checker` : null;
  const g3Allowed = GATE_ROLES.G3.includes(identity.role);
  const g3DisabledReason = !g3Allowed ? `Role ${identity.role} is not a Gate 3 checker` : null;

  const readOnlySuffix = readOnly ? ' Read-only demo snapshot — gate actions are disabled.' : '';

  if (item.canonicalState === 'queued') {
    return (
      <section className="reviewbar">
        <div className="reviewcopy">
          <span className="gate-icon">
            <Clock size={16} />
          </span>
          <span>
            <strong>Queued</strong>
            <span>Waiting for the previous story to finish.</span>
          </span>
        </div>
      </section>
    );
  }

  if (stage == null || stage === 'awaiting-G1') {
    const copy = (qualityBlocked ? 'Quality evaluation failed — approval is blocked until a passing revision.' : (actions.disabledReason ?? 'Approval unlocks planning.')) + readOnlySuffix;
    return (
      <section className="reviewbar">
        <div className="reviewcopy">
          <span className="gate-icon">
            <ShieldCheck size={16} />
          </span>
          <span>
            <strong>Gate 1 · Product approval</strong>
            <span>{copy}</span>
          </span>
        </div>
        <div className="review-actions">
          <TextField.Root
            className="approval-note"
            placeholder="Approval note (optional)"
            value={note}
            onChange={(e) => setNote(e.target.value)}
            disabled={readOnly}
          />
          <Button
            variant="outline"
            color="red"
            disabled={readOnly}
            onClick={() => actions.requestChanges.mutate()}
            loading={actions.requestChanges.isPending}
          >
            Request changes
          </Button>
          <Tooltip content={readOnly ? 'Read-only demo snapshot' : qualityBlocked ? 'Quality evaluation has not passed' : (actions.disabledReason ?? '')}>
            <Button
              color="green"
              disabled={readOnly || actions.approveDisabled || qualityBlocked}
              onClick={() => actions.approve.mutate(note)}
              loading={actions.approve.isPending}
            >
              Approve story
            </Button>
          </Tooltip>
        </div>
      </section>
    );
  }

  if (stage === 'awaiting-G2') {
    const copy = 'Approve the pull request or send it back to the build loop.' + readOnlySuffix;
    return (
      <section className="reviewbar">
        <div className="reviewcopy">
          <span className="gate-icon">
            <GitPullRequest size={16} />
          </span>
          <span>
            <strong>Gate 2 · PR review</strong>
            <span>{copy}</span>
            {approvals.length > 0 && (
              <span>
                {approvals.map((a) => (
                  <span key={a.who} className="pill pass" style={{ marginRight: 4 }}>
                    {a.role} ✓ {a.who}
                  </span>
                ))}
              </span>
            )}
          </span>
        </div>
        <div className="review-actions">
          <Tooltip content={readOnly ? 'Read-only demo snapshot' : (g2DisabledReason ?? '')}>
            <Button
              variant="outline"
              color="red"
              disabled={readOnly || !g2Allowed}
              onClick={() => actions.prRequestChanges.mutate()}
              loading={actions.prRequestChanges.isPending}
            >
              Request changes
            </Button>
          </Tooltip>
          <Tooltip content={readOnly ? 'Read-only demo snapshot' : (g2DisabledReason ?? '')}>
            <Button
              color="green"
              disabled={readOnly || !g2Allowed}
              onClick={() => actions.prApprove.mutate('')}
              loading={actions.prApprove.isPending}
            >
              Approve PR
            </Button>
          </Tooltip>
        </div>
      </section>
    );
  }

  if (stage === 'awaiting-G3') {
    const copy = 'Sign each release document in the Release tab. Any request for changes clears every signature.' + readOnlySuffix;
    return (
      <section className="reviewbar">
        <div className="reviewcopy">
          <span className="gate-icon">
            <Rocket size={16} />
          </span>
          <span>
            <strong>Gate 3 · Release sign-off</strong>
            <span>{copy}</span>
          </span>
        </div>
        <div className="review-actions">
          <Tooltip content={readOnly ? 'Read-only demo snapshot' : (g3DisabledReason ?? '')}>
            <Button
              variant="outline"
              color="red"
              disabled={readOnly || !g3Allowed}
              onClick={() => actions.releaseRequestChanges.mutate()}
              loading={actions.releaseRequestChanges.isPending}
            >
              Request changes
            </Button>
          </Tooltip>
          {onOpenRelease && (
            <Button variant="soft" onClick={onOpenRelease}>
              Open release tab
            </Button>
          )}
        </div>
      </section>
    );
  }

  if (approvals.length > 0) {
    const copy = stageSubtitle(item) + readOnlySuffix;
    return (
      <section className="reviewbar">
        <div className="reviewcopy">
          <span className="gate-icon">
            <Check size={16} />
          </span>
          <span>
            <strong>Gate approvals</strong>
            <span>{copy}</span>
            <span>
              {approvals.map((a) => (
                <span key={a.who} className="pill pass" style={{ marginRight: 4 }}>
                  {a.role}: {a.who}
                </span>
              ))}
            </span>
          </span>
        </div>
      </section>
    );
  }

  return null;
}
