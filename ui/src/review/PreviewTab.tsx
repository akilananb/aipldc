import { createElement, useMemo, type ReactElement } from 'react';
import Markdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';

interface Props {
  markdown: string;
  onLineSelect: (line: number) => void;
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

export default function PreviewTab({ markdown, onLineSelect }: Props) {
  const components = useMemo<Components>(() => {
    const comps: Record<string, (props: any) => ReactElement> = {};
    for (const tag of BLOCK_TAGS) {
      comps[tag] = (props: any) => {
        const { node, children, ...rest } = props;
        const line = node?.position?.start?.line;
        return createElement(
          tag,
          {
            ...rest,
            'data-line': typeof line === 'number' ? line : undefined,
            className: 'md-block',
            onClick: (e: any) => {
              e.stopPropagation();
              if (typeof line === 'number') onLineSelect(line);
            },
          },
          children,
        );
      };
    }
    return comps as unknown as Components;
  }, [onLineSelect]);

  return (
    <div className="story-preview">
      <Markdown remarkPlugins={[remarkGfm]} components={components}>
        {markdown}
      </Markdown>
    </div>
  );
}
