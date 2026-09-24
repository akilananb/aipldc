import { useEffect, useState, type ReactNode } from 'react';
import { Link, useMatch, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Bot, FileText, FolderKanban, Moon, Radio, Repeat2, Search, ShieldCheck, Sun } from 'lucide-react';
import { api } from '../api';
import { getAppearance, setAppearance, useAppearance } from '../theme';
import { itemNeedsAttention } from '../ui-utils';
import { useGateRolesByProject, useProjects } from '../useProject';
import { setAuth, useAuth, useIdentity } from '../identity';
import IdentitySwitcher from './IdentitySwitcher';
import SignIn from './SignIn';
import UserMenu from './UserMenu';
import AgentPresenceStatus from './AgentPresenceStatus';
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
  const onAgentsRoute = useMatch('/agents');
  const onProjectsRoute = useMatch('/projects');
  const itemMatch = useMatch('/items/:id');
  const [paletteOpen, setPaletteOpen] = useState(false);

  // Who am I, and how does this backend authenticate? Drives dev-header vs session behaviour in
  // api.ts and whether the app renders at all (OIDC mode without a session shows SignIn).
  const auth = useAuth();
  const meQuery = useQuery({ queryKey: ['me'], queryFn: api.me, refetchOnWindowFocus: true, retry: 1 });
  useEffect(() => {
    if (meQuery.data) setAuth(meQuery.data);
  }, [meQuery.data]);
  const devMode = auth.mode === 'dev-headers';
  const signedIn = devMode || (auth.authenticated && !!auth.role);
  const needsSignIn = !devMode && auth.mode !== 'unknown' && !signedIn;

  const itemsQuery = useQuery({
    queryKey: ['items'],
    queryFn: api.listItems,
    refetchInterval: 2000,
    enabled: signedIn,
  });
  const rolesByProject = useGateRolesByProject();
  const topLevel = (itemsQuery.data ?? []).filter((i) => i.kind !== 'task');
  const runningCount = topLevel.filter((i) => i.activeRun != null).length;
  const attentionCount = topLevel.filter((i) => itemNeedsAttention(i, identity.role, rolesByProject)).length;

  const breadcrumbItem = useQuery({
    queryKey: ['item', itemMatch?.params.id],
    queryFn: () => api.getItem(itemMatch!.params.id!),
    enabled: signedIn && !!itemMatch?.params.id,
  });

  const projects = useProjects();
  const projectName = projects.data?.find((p) => p.id === breadcrumbItem.data?.profile)?.name ?? breadcrumbItem.data?.profile ?? '…';

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

  if (needsSignIn) {
    return <SignIn auth={auth} />;
  }
  if (!signedIn) {
    // Waiting for /api/me - render nothing rather than firing requests that would 401.
    return null;
  }

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
          <Link to="/agents" className={onAgentsRoute ? 'active' : ''}>
            <Radio size={16} />
            <span>Agents</span>
          </Link>
          <Link to="/projects" className={onProjectsRoute ? 'active' : ''}>
            <FolderKanban size={16} />
            <span>Projects</span>
          </Link>
        </nav>

        <div className="side-foot">
          <div className="status">
            <i style={{ background: itemsQuery.isError ? 'var(--red)' : 'var(--green)' }} />
            <span>{itemsQuery.isError ? 'Control plane unreachable' : 'Control plane connected'}</span>
          </div>
          <AgentPresenceStatus />
          {devMode ? <IdentitySwitcher /> : <UserMenu />}
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
              <span className="crumb">
                / {projectName} / {breadcrumbItem.data?.boardId ?? '…'}
              </span>
            </>
          ) : onAgentsRoute ? (
            <span className="crumb">Agents</span>
          ) : onProjectsRoute ? (
            <span className="crumb">Projects</span>
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
