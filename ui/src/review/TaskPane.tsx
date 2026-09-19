import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '@radix-ui/themes';
import { formatTokens } from '../ui-utils';
import RunTimeline from './RunTimeline';
import { STATUS_LABEL } from './ExecutionGraph';
import type { TaskNode } from './useTaskGraph';

interface Props {
  node: TaskNode;
  onClose: () => void;
}

export default function TaskPane({ node, onClose }: Props) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  const latest = node.runs[0];
  const iterations = latest?.iterations ?? node.build?.iterations ?? null;
  const tokens = latest?.tokens ?? null;
  const verifier = node.build?.verifier ?? latest?.outcome ?? null;
  const brief = node.brief;

  return (
    <>
      <div className="pane-head">
        <div>
          <h2>{node.item.title}</h2>
          <p className="inspector-sub">
            {node.taskId ?? node.item.boardId} · {STATUS_LABEL[node.status]}
          </p>
        </div>
        <button
          type="button"
          className="icon-btn"
          style={{ minHeight: 'auto', width: 22, height: 22, padding: 0 }}
          aria-label="Close task"
          onClick={onClose}
        >
          ×
        </button>
      </div>

      {brief?.scenario && (
        <div className="anchor">
          <span className="anchor-label">Proves</span>
          <strong>{brief.scenario}</strong>
        </div>
      )}

      <div className="pane-stats">
        <div className="pane-stat">
          <span>Iterations</span>
          <strong>{iterations ?? '—'}</strong>
        </div>
        <div className="pane-stat">
          <span>Tokens</span>
          <strong>{tokens != null ? formatTokens(tokens) : '—'}</strong>
        </div>
        <div className="pane-stat">
          <span>Verifier</span>
          <strong style={{ color: verifier === 'green' ? 'var(--green)' : verifier === 'red' ? 'var(--red)' : undefined }}>
            {verifier ?? '—'}
          </strong>
        </div>
      </div>

      <div className="sidecard">
        <h3>Brief</h3>
        {brief?.area && (
          <div className="kv">
            <span>Area</span>
            <span>{brief.area}</span>
          </div>
        )}
        {brief && brief.touches.length > 0 && (
          <div className="kv">
            <span>Touches</span>
            <span>{brief.touches.join(', ')}</span>
          </div>
        )}
        {brief?.test && (
          <div className="kv">
            <span>Test</span>
            <span>{brief.test}</span>
          </div>
        )}
        {brief && brief.blockedBy.length > 0 && (
          <div className="kv">
            <span>Blocked by</span>
            <span>{brief.blockedBy.join(', ')}</span>
          </div>
        )}
        {node.build?.commit && (
          <div className="kv">
            <span>Commit</span>
            <span>{node.build.commit.slice(0, 12)}</span>
          </div>
        )}
        {node.build?.escalation && (
          <div className="kv">
            <span>Escalation</span>
            <span>{node.build.escalation}</span>
          </div>
        )}
        {brief?.summary && (
          <p className="inspector-sub" style={{ marginTop: '.5rem' }}>
            {brief.summary}
          </p>
        )}
      </div>

      <div className="sidecard">
        <h3>Build runs</h3>
        {node.runs.length ? <RunTimeline runs={node.runs} now={now} /> : <p className="inspector-sub">No build runs yet.</p>}
      </div>

      <Button asChild size="1" variant="soft" style={{ marginTop: '.8rem' }}>
        <Link to={`/items/${encodeURIComponent(node.item.id)}`}>Open task</Link>
      </Button>
    </>
  );
}
