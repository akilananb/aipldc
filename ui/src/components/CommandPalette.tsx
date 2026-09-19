import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useLocation, useMatch, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Bot, FileText, History, ShieldCheck } from 'lucide-react';
import { Dialog } from '@radix-ui/themes';
import { api } from '../api';
import { requestCompose } from '../composer';

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

interface Entry {
  id: string;
  label: string;
  icon: ReactNode;
  kbd?: string;
  run: () => void;
}

export default function CommandPalette({ open, onOpenChange }: Props) {
  const navigate = useNavigate();
  const location = useLocation();
  const itemMatch = useMatch('/items/:id');
  const [query, setQuery] = useState('');
  const [highlight, setHighlight] = useState(0);

  const itemsQuery = useQuery({ queryKey: ['items'], queryFn: api.listItems, enabled: open });
  const currentItemQuery = useQuery({
    queryKey: ['item', itemMatch?.params.id],
    queryFn: () => api.getItem(itemMatch!.params.id!),
    enabled: open && !!itemMatch?.params.id,
  });

  useEffect(() => {
    if (!open) {
      setQuery('');
      setHighlight(0);
    }
  }, [open]);

  function close() {
    onOpenChange(false);
    setQuery('');
  }

  function gotoTab(tab: string) {
    navigate({ pathname: location.pathname, search: `?tab=${tab}` });
  }

  const entries = useMemo<Entry[]>(() => {
    const needle = query.trim().toLowerCase();
    const result: Entry[] = [];

    if (needle) {
      const itemEntries = (itemsQuery.data ?? [])
        .filter((i) => i.kind !== 'task')
        .filter((i) => i.title.toLowerCase().includes(needle) || i.boardId.toLowerCase().includes(needle))
        .slice(0, 8)
        .map((i) => ({
          id: `item:${i.id}`,
          label: i.title,
          kbd: i.boardId,
          icon: <FileText size={16} />,
          run: () => navigate(`/items/${encodeURIComponent(i.id)}`),
        }));
      result.push(...itemEntries);
    }

    if (itemMatch && currentItemQuery.data?.kind === 'story') {
      const actions: Entry[] = [
        {
          id: 'action:preview',
          label: 'Open story preview',
          kbd: 'P',
          icon: <FileText size={16} />,
          run: () => gotoTab('preview'),
        },
        {
          id: 'action:source',
          label: 'Open story source',
          kbd: 'S',
          icon: <FileText size={16} />,
          run: () => gotoTab('source'),
        },
        {
          id: 'action:quality',
          label: 'Open quality review',
          kbd: 'Q',
          icon: <ShieldCheck size={16} />,
          run: () => gotoTab('quality'),
        },
        {
          id: 'action:activity',
          label: 'Open activity history',
          kbd: 'H',
          icon: <History size={16} />,
          run: () => gotoTab('activity'),
        },
        {
          id: 'action:ask',
          label: 'Ask an agent',
          kbd: 'A',
          icon: <Bot size={16} />,
          run: () => {
            gotoTab('preview');
            requestCompose('@analyst ');
          },
        },
      ];
      result.push(...actions.filter((a) => !needle || a.label.toLowerCase().includes(needle)));
    }

    return result;
  }, [query, itemsQuery.data, itemMatch, currentItemQuery.data?.kind, navigate, location.pathname]);

  function run(entry: Entry) {
    entry.run();
    close();
  }

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Content className="command" style={{ padding: 0 }} aria-describedby={undefined}>
        <Dialog.Title className="sr-only">Search workspace</Dialog.Title>
        <input
          id="command-input"
          autoFocus
          placeholder="Search items, artifacts, agents, or actions…"
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setHighlight(0);
          }}
          onKeyDown={(e) => {
            if (e.key === 'ArrowDown') {
              e.preventDefault();
              setHighlight((h) => (entries.length === 0 ? 0 : (h + 1) % entries.length));
            } else if (e.key === 'ArrowUp') {
              e.preventDefault();
              setHighlight((h) => (entries.length === 0 ? 0 : (h - 1 + entries.length) % entries.length));
            } else if (e.key === 'Enter') {
              e.preventDefault();
              const entry = entries[highlight];
              if (entry) run(entry);
            }
          }}
        />
        <div className="command-list">
          {entries.length === 0 && <div className="command-item">No matches</div>}
          {entries.map((entry, i) => (
            <button
              key={entry.id}
              type="button"
              className="command-item"
              data-highlighted={i === highlight}
              onMouseEnter={() => setHighlight(i)}
              onClick={() => run(entry)}
            >
              {entry.icon}
              {entry.label}
              {entry.kbd && <kbd>{entry.kbd}</kbd>}
            </button>
          ))}
        </div>
      </Dialog.Content>
    </Dialog.Root>
  );
}
