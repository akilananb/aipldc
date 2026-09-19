import { useEffect, useState, type ReactNode } from 'react';
import { Link, useMatch, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Bot, FileText, Moon, Repeat2, Search, ShieldCheck, Sun } from 'lucide-react';
import { api } from '../api';
import { getAppearance, setAppearance, useAppearance } from '../theme';
import { needsAttention } from '../ui-utils';
import { useIdentity } from '../identity';
import IdentitySwitcher from './IdentitySwitcher';
import CommandPalette from './CommandPalette';

interface Props {
  children: ReactNode;
}

type View = 'items' | 'running' | 'attention';

export default function AppShell({ children }: Props) {
  const identity = useIdentity();
  const appearance = useAppearance();
  const [params] = useSearchParams();
  const view: View = params.get('view') === 'running' ? 'running' : params.get('view') === 'attention' ? 'attention' : 'items';
  const onItemsRoute = useMatch('/');
  const itemMatch = useMatch('/items/:id');
  const [paletteOpen, setPaletteOpen] = useState(false);

  const itemsQuery = useQuery({
    queryKey: ['items'],
    queryFn: api.listItems,
    refetchInterval: 2000,
  });
  const topLevel = (itemsQuery.data ?? []).filter((i) => i.kind !== 'task');
  const runningCount = topLevel.filter((i) => i.activeRun != null).length;
  const attentionCount = topLevel.filter(
    (i) => i.snapshot == null && needsAttention(i.canonicalState, identity.role),
  ).length;

  const breadcrumbItem = useQuery({
    queryKey: ['item', itemMatch?.params.id],
    queryFn: () => api.getItem(itemMatch!.params.id!),
    enabled: !!itemMatch?.params.id,
  });

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setPaletteOpen(true);
      }
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  return (
    <div className="app">
      <aside className="side">
        <Link to="/" className="brand">
          <span className="mark">
            <Repeat2 size={16} />
          </span>
          <span className="brand-copy">
            eLoop<span style={{ color: 'var(--accent)' }}>.</span>ai
            <small>Product Development Lifecycle</small>
          </span>
        </Link>

        <div className="nav-label">Workspace</div>
        <nav className="nav">
          <Link to="/" className={onItemsRoute && view === 'items' ? 'active' : ''}>
            <FileText size={16} />
            <span>Items</span>
            {topLevel.length > 0 && <span className="badge">{topLevel.length}</span>}
          </Link>
          <Link to="/?view=running" className={onItemsRoute && view === 'running' ? 'active' : ''}>
            <Bot size={16} />
            <span>Agent Runs</span>
            {runningCount > 0 && <span className="badge">{runningCount}</span>}
          </Link>
          <Link to="/?view=attention" className={onItemsRoute && view === 'attention' ? 'active' : ''}>
            <ShieldCheck size={16} />
            <span>Needs review</span>
            {attentionCount > 0 && <span className="badge">{attentionCount}</span>}
          </Link>
        </nav>

        <div className="side-foot">
          <div className="status">
            <i style={{ background: itemsQuery.isError ? 'var(--red)' : 'var(--green)' }} />
            <span>{itemsQuery.isError ? 'Control plane unreachable' : 'Control plane connected'}</span>
          </div>
          <IdentitySwitcher />
        </div>
      </aside>

      <main className="main">
        <header className="topbar">
          <Link className="mobile-brand" to="/">
            <span className="mark">
              <Repeat2 size={16} />
            </span>
            eLoop.ai
          </Link>
          {itemMatch ? (
            <>
              <Link className="back" to="/">
                ← Items
              </Link>
              <span className="crumb">/ {breadcrumbItem.data?.boardId ?? '…'}</span>
            </>
          ) : (
            <span className="crumb">Items</span>
          )}
          <div className="top-actions">
            <button type="button" className="search" onClick={() => setPaletteOpen(true)}>
              <Search size={16} />
              <span>Search workspace…</span>
              <kbd>⌘ K</kbd>
            </button>
            <button
              type="button"
              className="icon-btn"
              aria-label="Toggle theme"
              onClick={() => setAppearance(getAppearance() === 'dark' ? 'light' : 'dark')}
            >
              {appearance === 'dark' ? <Moon size={16} /> : <Sun size={16} />}
            </button>
          </div>
        </header>
        <div className="workspace">{children}</div>
      </main>

      <CommandPalette open={paletteOpen} onOpenChange={setPaletteOpen} />
    </div>
  );
}
