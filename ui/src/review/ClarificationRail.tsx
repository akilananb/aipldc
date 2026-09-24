import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Button } from '@radix-ui/themes';
import { api, errorMessage } from '../api';
import { splitGrillRecommendation } from '../ui-utils';
import type { ClarificationScope } from './useClarification';
import { useClarification } from './useClarification';
import ProjectMetaLink from '../components/ProjectMetaLink';
import type { ItemDetail } from '../types';

interface Props {
  item: ItemDetail;
  scope: ClarificationScope;
}

function truncate(s: string, n: number): string {
  return s.length > n ? `${s.slice(0, n - 1).trimEnd()}…` : s;
}

export default function ClarificationRail({ item, scope }: Props) {
  const queryClient = useQueryClient();
  const { coverage, answered, open, confirmation, parked, rounds, canProceed, resolved } = useClarification(item, scope);

  const proceed = useMutation({
    mutationFn: () => api.proceedGrill(item.id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['grill', item.id] });
      void queryClient.invalidateQueries({ queryKey: ['board-comments', item.id] });
      void queryClient.invalidateQueries({ queryKey: ['item', item.id] });
      toast.success('Proceeding to story drafting');
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  return (
    <>
      <h2>Interview context</h2>
      <p className="inspector-sub">Updates after every submitted batch.</p>

      <div className="sidecard">
        <h3>{item.kind === 'feature' ? 'Feature' : 'Story'}</h3>
        <div className="kv">
          <span>Title</span>
          <span>{item.title}</span>
        </div>
        <div className="kv">
          <span>Source</span>
          <span>
            Board {item.boardId} · <ProjectMetaLink profile={item.profile} />
          </span>
        </div>
        {item.description && <p className="inspector-sub clamp">{item.description}</p>}
      </div>

      <div className="sidecard">
        <h3>Category coverage</h3>
        {coverage.length === 0 ? (
          <p className="inspector-sub">No questions yet.</p>
        ) : (
          coverage.map((c) => (
            <div className="coverage-row" key={c.category}>
              <span>{c.label}</span>
              <span>{c.pct}%</span>
              <div className="stat-bar">
                <i style={{ width: `${c.pct}%`, background: c.pct === 100 ? 'var(--green)' : c.pct > 0 ? 'var(--amber)' : 'var(--line)' }} />
              </div>
            </div>
          ))
        )}
        <p className="inspector-sub">Answered or parked questions per category.</p>
      </div>

      <div className="sidecard">
        <h3>Decision register</h3>
        {answered.length === 0 ? (
          <p className="inspector-sub">No decisions recorded yet.</p>
        ) : (
          <ul className="register">
            {answered.map((q) => (
              <li key={q.id}>
                <i className="dot" />
                <span>{truncate(splitGrillRecommendation(q.question).body, 72)}</span>
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="sidecard">
        <h3>Interview policy</h3>
        <div className="kv">
          <span>Round</span>
          <span>{rounds}</span>
        </div>
        <div className="kv">
          <span>Open questions</span>
          <span>{open.length + (confirmation ? 1 : 0)}</span>
        </div>
        <div className="kv">
          <span>Parked</span>
          <span>{parked.length}</span>
        </div>
        <div className="kv">
          <span>Next agent</span>
          <span>{scope === 'intake' ? 'PO agent' : 'Build worker'}</span>
        </div>
        {scope === 'intake' && (
          <>
            <Button variant="soft" style={{ marginTop: '.6rem', width: '100%' }} disabled={!canProceed || resolved || proceed.isPending} onClick={() => proceed.mutate()}>
              Proceed to story drafting
            </Button>
            <p className="inspector-sub">
              {rounds < 2 ? `Parks every open question; available after two rounds (${rounds}/2).` : 'Parks every open question and hands off to the PO agent.'}
            </p>
          </>
        )}
      </div>
    </>
  );
}
