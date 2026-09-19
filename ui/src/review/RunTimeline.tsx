import RelativeTime from '../components/RelativeTime';
import { agentRunLabel, formatTokens } from '../ui-utils';
import type { AgentRun } from '../types';

interface Props {
  runs: AgentRun[];
  now: number;
}

export function formatDuration(startedAt: string, endIso: string | null): string {
  if (!endIso) return '';
  const startMs = new Date(startedAt).getTime();
  const endMs = new Date(endIso).getTime();
  if (Number.isNaN(startMs) || Number.isNaN(endMs)) return '';
  const totalSec = Math.max(0, Math.round((endMs - startMs) / 1000));
  if (totalSec === 0) return '';
  if (totalSec < 60) return `${totalSec}s`;
  const min = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  return `${min}m ${sec}s`;
}

export function trackModifier(run: AgentRun): string {
  if (run.status === 'running') return 'running';
  if (run.status === 'abandoned') return 'idle';
  if (run.outcome === 'error' || run.outcome === 'red') return 'error';
  return '';
}

/** Renders a list of agent runs as `.activity-row`s — shared by ActivityTab (an item's full
 * agent history) and TaskPane (a single task's build-worker runs). */
export default function RunTimeline({ runs, now }: Props) {
  return (
    <div>
      {runs.map((run) => {
        const end = run.finishedAt ?? (run.status === 'running' ? new Date(now).toISOString() : null);
        const duration = formatDuration(run.startedAt, end);
        return (
          <div key={run.id} className="activity-row">
            <time>
              <RelativeTime iso={run.startedAt} />
            </time>
            <span className="track">
              <i className={trackModifier(run)} />
            </span>
            <span className="activity-body">
              <strong>{agentRunLabel(run)}</strong>
              <span>
                {run.outcome ?? run.status}
                {duration ? ` · ${duration}` : ''}
                {run.iterations != null ? ` · ${run.iterations} iteration${run.iterations === 1 ? '' : 's'}` : ''}
                {run.tokens != null ? ` · ${formatTokens(run.tokens)} tokens` : ''}
                {run.traceUrl && (
                  <>
                    {' · '}
                    <a href={run.traceUrl} target="_blank" rel="noreferrer">
                      trace
                    </a>
                  </>
                )}
              </span>
            </span>
          </div>
        );
      })}
    </div>
  );
}
