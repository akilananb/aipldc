import type { TaskColumn, TaskNode, TaskStatus } from './useTaskGraph';

export const STATUS_LABEL: Record<TaskStatus, string> = {
  verified: 'verified',
  failed: 'failed',
  running: 'building',
  blocked: 'blocked',
  queued: 'queued',
};

function footText(n: TaskNode): string {
  if (n.status === 'blocked') {
    return n.brief && n.brief.blockedBy.length > 0 ? `blocked by ${n.brief.blockedBy.join(', ')}` : 'blocked';
  }
  if (n.status === 'running') return 'building';
  const iterations = n.runs[0]?.iterations ?? n.build?.iterations ?? null;
  if (iterations != null) return `${iterations} iteration${iterations === 1 ? '' : 's'}`;
  const touches = n.brief?.touches.length ?? 0;
  if (touches > 0) return `${touches} file${touches === 1 ? '' : 's'}`;
  return '';
}

interface Props {
  columns: TaskColumn[];
  selectedId: string | null;
  onSelect: (id: string) => void;
}

export default function ExecutionGraph({ columns, selectedId, onSelect }: Props) {
  return (
    <div className="graph">
      {columns.map((col) => (
        <div className="graph-col" key={col.label}>
          <h4>{col.label}</h4>
          {col.nodes.map((n) => (
            <button
              type="button"
              key={n.item.id}
              className={`task-card ${n.status}${n.item.id === selectedId ? ' selected' : ''}`}
              onClick={() => onSelect(n.item.id)}
            >
              <span className="task-card-head">
                <i className="dot" />
                {n.taskId ?? n.item.boardId} · {STATUS_LABEL[n.status]}
              </span>
              <strong>{n.item.title}</strong>
              <span className="task-card-foot">
                <span>{footText(n)}</span>
                {n.brief?.area && <span className="chip">{n.brief.area}</span>}
              </span>
            </button>
          ))}
        </div>
      ))}
    </div>
  );
}
