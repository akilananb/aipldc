import { useEffect, useMemo, useState, type MouseEvent } from 'react';
import { Check, ChevronDown, ChevronRight, Search } from 'lucide-react';
import { Select } from '@radix-ui/themes';
import type { LineRange } from '../ui-utils';
import { parseStoryDoc } from '../ui-utils';
import type { ScenarioReview } from '../types';

interface Props {
  markdown: string;
  reviews: ScenarioReview[];
  canReview: boolean;
  reviewing: string | null;
  onReviewScenario: (scenario: string, status: 'meets' | 'not-reviewed') => void;
  onLineSelect: (line: number, shiftKey: boolean) => void;
  onRangeSelect: (start: number, end: number) => void;
  selectedRange: LineRange | null;
}

type ReviewFilter = 'all' | 'meets' | 'not-reviewed';

export default function PreviewTab({
  markdown,
  reviews,
  canReview,
  reviewing,
  onReviewScenario,
  onLineSelect,
  onRangeSelect,
  selectedRange,
}: Props) {
  const doc = useMemo(() => parseStoryDoc(markdown), [markdown]);
  const isFallback = !doc.story && doc.scenarios.length === 0;

  const [ctxOpen, setCtxOpen] = useState(isFallback);
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<ReviewFilter>('all');
  const [expanded, setExpanded] = useState<Set<number>>(() => new Set([0]));

  useEffect(() => {
    setExpanded(new Set([0]));
  }, [markdown]);

  const blockClick = (line: number) => (e: MouseEvent) => {
    e.stopPropagation();
    if (!window.getSelection()?.isCollapsed) return;
    onLineSelect(line, e.shiftKey);
  };

  const rowProps = (line: number) => ({
    'data-line': line,
    className:
      'md-block' +
      (!!selectedRange && line >= selectedRange.start && line <= selectedRange.end ? ' md-block-selected' : ''),
    onClick: blockClick(line),
  });

  const handleMouseUp = () => {
    const sel = window.getSelection();
    if (!sel || sel.isCollapsed) return;
    const blockOf = (n: Node | null) => (n instanceof Element ? n : n?.parentElement)?.closest('[data-line]');
    const a = blockOf(sel.anchorNode);
    const b = blockOf(sel.focusNode);
    if (!a || !b) return;
    const la = Number(a.getAttribute('data-line'));
    const lb = Number(b.getAttribute('data-line'));
    if (Number.isFinite(la) && Number.isFinite(lb)) onRangeSelect(Math.min(la, lb), Math.max(la, lb));
  };

  function statusOf(name: string): 'meets' | 'not-reviewed' {
    return reviews.find((r) => r.scenario === name)?.status === 'meets' ? 'meets' : 'not-reviewed';
  }

  function toggleScenario(index: number) {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(index)) next.delete(index);
      else next.add(index);
      return next;
    });
  }

  const needle = query.trim().toLowerCase();
  const visibleScenarios = doc.scenarios.filter((s) => {
    const status = statusOf(s.name);
    if (filter !== 'all' && filter !== status) return false;
    if (!needle) return true;
    if (s.name.toLowerCase().includes(needle)) return true;
    return s.steps.some((step) => step.text.toLowerCase().includes(needle));
  });

  return (
    <div
      className="story-preview"
      onMouseDown={(e) => {
        if (e.shiftKey) e.preventDefault();
      }}
      onMouseUp={handleMouseUp}
    >
      {doc.story && (
        <section className="story-card">
          <h3>User story</h3>
          <div className="story-rows">
            {doc.story.rows.map((row) => {
              const rp = rowProps(row.line);
              return (
                <div key={row.line} {...rp} className={'story-row ' + rp.className}>
                  {row.keyword && <span className="kw-chip">{row.keyword}</span>}
                  <span className="row-text">{row.text}</span>
                </div>
              );
            })}
          </div>
        </section>
      )}

      {doc.context.length > 0 && (
        <section className="ctx">
          <button
            type="button"
            className="ctx-head"
            aria-expanded={ctxOpen}
            onClick={() => setCtxOpen((o) => !o)}
          >
            <strong>Context &amp; constraints</strong>
            <span className="ctx-count">
              · {doc.rules} rule{doc.rules === 1 ? '' : 's'}
            </span>
            <ChevronRight className="chev" size={14} />
          </button>
          {ctxOpen &&
            doc.context.map((section) => (
              <div key={section.heading} className="ctx-section">
                <h4>{section.heading}</h4>
                {section.lines.map((line) => {
                  const rp = rowProps(line.line);
                  return (
                    <div key={line.line} {...rp} className={'ctx-line ' + rp.className}>
                      {line.text}
                    </div>
                  );
                })}
              </div>
            ))}
        </section>
      )}

      {doc.criteriaLine != null && (
        <section className="ac">
          <div className="ac-head">
            <h3>
              Acceptance criteria{' '}
              <span className="ac-count">
                · {doc.scenarios.length} scenario{doc.scenarios.length === 1 ? '' : 's'}
              </span>
            </h3>
            <div className="ac-tools">
              <label className="ac-search">
                <Search size={14} />
                <input
                  placeholder="Search scenarios…"
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                />
              </label>
              <Select.Root value={filter} onValueChange={(v) => setFilter(v as ReviewFilter)} size="2">
                <Select.Trigger />
                <Select.Content>
                  <Select.Item value="all">All</Select.Item>
                  <Select.Item value="meets">Meets criteria</Select.Item>
                  <Select.Item value="not-reviewed">Not reviewed</Select.Item>
                </Select.Content>
              </Select.Root>
            </div>
          </div>

          {visibleScenarios.length === 0 ? (
            <p className="ac-empty">No scenarios match.</p>
          ) : (
            visibleScenarios.map((scenario) => {
              const status = statusOf(scenario.name);
              const open = expanded.has(scenario.index);
              const selected =
                !!selectedRange && selectedRange.start === scenario.start && selectedRange.end === scenario.end;
              return (
                <div
                  key={scenario.index}
                  className={'sc' + (open ? ' open' : '') + (selected ? ' md-block-selected' : '')}
                  data-line={scenario.start}
                >
                  <div className="sc-head">
                    <button
                      type="button"
                      className="sc-toggle"
                      aria-expanded={open}
                      onClick={() => toggleScenario(scenario.index)}
                    >
                      {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                    </button>
                    <span className="sc-num">{scenario.index + 1}</span>
                    <button
                      type="button"
                      className="sc-title"
                      onClick={(e) => {
                        e.stopPropagation();
                        onRangeSelect(scenario.start, scenario.end);
                      }}
                    >
                      {scenario.name}
                    </button>
                    <button
                      type="button"
                      className={'sc-status ' + status}
                      disabled={!canReview || reviewing === scenario.name}
                      onClick={(e) => {
                        e.stopPropagation();
                        onReviewScenario(scenario.name, status === 'meets' ? 'not-reviewed' : 'meets');
                      }}
                      title={canReview ? 'Toggle meets criteria' : 'Gate 1 checkers can mark this'}
                    >
                      {status === 'meets' ? (
                        <>
                          <Check size={12} /> Meets criteria
                        </>
                      ) : (
                        'Not reviewed'
                      )}
                    </button>
                  </div>
                  {open && (
                    <div className="gwt-rows">
                      {scenario.steps.map((step) => {
                        const rp = rowProps(step.line);
                        return (
                          <div key={step.line} {...rp} className={'gwt-row ' + rp.className}>
                            <span className="kw-chip step">{step.keyword}</span>
                            <span className="row-text">{step.text}</span>
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>
              );
            })
          )}
        </section>
      )}
    </div>
  );
}
