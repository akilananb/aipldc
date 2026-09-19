import { formatTokens } from '../ui-utils';
import type { TaskGraphSummary } from './useTaskGraph';

interface Props {
  summary: TaskGraphSummary;
}

export default function BuildEvidence({ summary }: Props) {
  if (summary.total === 0) return null;

  const verifiedPct = Math.round((summary.verified / summary.total) * 100);
  const verifierPct = summary.buildRuns ? Math.round((summary.green / summary.buildRuns) * 100) : 0;

  return (
    <div className="evidence">
      <div className="evidence-row">
        <div>
          <strong>Tasks verified</strong>
          <small>
            {summary.failed} failed · {summary.running} building
          </small>
        </div>
        <span>
          {summary.verified} / {summary.total}
        </span>
        <small>
          {summary.blocked} blocked · {summary.queued} queued
        </small>
        <span className={`pct ${summary.verified === summary.total ? 'ok' : 'warn'}`}>{verifiedPct}%</span>
      </div>
      <div className="evidence-row">
        <div>
          <strong>Verifier</strong>
          <small>Build-worker outcomes</small>
        </div>
        <span>{summary.green} green</span>
        <small>{summary.red} red</small>
        <span className={`pct ${summary.red === 0 && summary.green > 0 ? 'ok' : 'warn'}`}>{verifierPct}%</span>
      </div>
      <div className="evidence-row">
        <div>
          <strong>Waves</strong>
          <small>Parallel groups from tasks.md</small>
        </div>
        <span>{summary.waves}</span>
        <small>{summary.waves === 0 ? 'no tasks.md' : ''}</small>
        <span />
      </div>
      <div className="evidence-row">
        <div>
          <strong>Build runs</strong>
          <small>
            {summary.iterations} iterations · {formatTokens(summary.tokens)} tokens
          </small>
        </div>
        <span>{summary.buildRuns}</span>
        <small />
        <span />
      </div>
    </div>
  );
}
