import { createElement, useMemo, type ReactElement } from 'react';
import { MousePointerClick } from 'lucide-react';
import { Flex, Text } from '@radix-ui/themes';
import Markdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';
import type { LineRange } from '../ui-utils';

interface Props {
  markdown: string;
  onLineSelect: (line: number, shiftKey: boolean) => void;
  onRangeSelect: (start: number, end: number) => void;
  selectedRange: LineRange | null;
}

// Block-level elements that carry a source line number. We attach `data-line`
// and a click handler directly to the element (rather than wrapping in a
// <div>) so list and table nesting stays valid HTML.
const BLOCK_TAGS = [
  'p',
  'h1',
  'h2',
  'h3',
  'h4',
  'h5',
  'h6',
  'blockquote',
  'pre',
  'ul',
  'ol',
  'li',
  'table',
  'thead',
  'tbody',
  'tr',
  'th',
  'td',
  'hr',
  'dl',
  'dt',
  'dd',
];

export default function PreviewTab({ markdown, onLineSelect, onRangeSelect, selectedRange }: Props) {
  const components = useMemo<Components>(() => {
    const comps: Record<string, (props: any) => ReactElement> = {};
    for (const tag of BLOCK_TAGS) {
      comps[tag] = (props: any) => {
        const { node, children, ...rest } = props;
        const line = node?.position?.start?.line;
        const selected =
          selectedRange && typeof line === 'number' && line >= selectedRange.start && line <= selectedRange.end;
        return createElement(
          tag,
          {
            ...rest,
            'data-line': typeof line === 'number' ? line : undefined,
            className: 'md-block' + (selected ? ' md-block-selected' : ''),
            onClick: (e: any) => {
              e.stopPropagation();
              if (!window.getSelection()?.isCollapsed) return;
              if (typeof line === 'number') onLineSelect(line, e.shiftKey);
            },
          },
          children,
        );
      };
    }
    return comps as unknown as Components;
  }, [onLineSelect, selectedRange]);

  const handleMouseUp = () => {
    const sel = window.getSelection();
    if (!sel || sel.isCollapsed) return;
    const blockOf = (n: Node | null) =>
      (n instanceof Element ? n : n?.parentElement)?.closest('[data-line]');
    const a = blockOf(sel.anchorNode);
    const b = blockOf(sel.focusNode);
    if (!a || !b) return;
    const la = Number(a.getAttribute('data-line'));
    const lb = Number(b.getAttribute('data-line'));
    if (Number.isFinite(la) && Number.isFinite(lb)) onRangeSelect(Math.min(la, lb), Math.max(la, lb));
  };

  return (
    <div>
      <Flex align="center" gap="1" mb="3" style={{ color: 'var(--gray-9)' }}>
        <MousePointerClick size={14} />
        <Text size="1" color="gray">
          Click a block to anchor a comment · drag across blocks for a range · Shift-click to extend
        </Text>
      </Flex>
      <div
        className="story-preview review-md"
        onMouseDown={(e) => {
          if (e.shiftKey) e.preventDefault();
        }}
        onMouseUp={handleMouseUp}
      >
        <Markdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeHighlight]} components={components}>
          {markdown}
        </Markdown>
      </div>
    </div>
  );
}
