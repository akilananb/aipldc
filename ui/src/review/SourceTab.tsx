import { useEffect, useMemo, useRef } from 'react';
import CodeMirror from '@uiw/react-codemirror';
import { markdown as markdownLang } from '@codemirror/lang-markdown';
import { StateEffect, StateField, type Range } from '@codemirror/state';
import { Decoration, EditorView, gutter, GutterMarker, type DecorationSet } from '@codemirror/view';
import type { Comment } from '../types';

interface Props {
  markdown: string;
  comments: Comment[];
}

const setCommentsEffect = StateEffect.define<Comment[]>();

const commentsField = StateField.define<Comment[]>({
  create: () => [],
  update(value, tr) {
    for (const e of tr.effects) {
      if (e.is(setCommentsEffect)) value = e.value;
    }
    return value;
  },
});

const decorationsField = StateField.define<DecorationSet>({
  create: () => Decoration.none,
  update(deco, tr) {
    deco = deco.map(tr.changes);
    if (tr.effects.some((e) => e.is(setCommentsEffect))) {
      const comments = tr.state.field(commentsField);
      const doc = tr.state.doc;
      const ranges: Range<Decoration>[] = [];
      for (const c of comments) {
        const line = c.anchor?.line;
        if (typeof line !== 'number' || line < 1 || line > doc.lines) continue;
        ranges.push(Decoration.line({ class: 'cm-comment-range' }).range(doc.line(line).from));
      }
      deco = Decoration.set(ranges, true);
    }
    return deco;
  },
  provide: (f) => EditorView.decorations.from(f),
});

class CommentGutterMarker extends GutterMarker {
  constructor(private readonly count: number) {
    super();
  }

  eq(other: CommentGutterMarker): boolean {
    return other.count === this.count;
  }

  toDOM(): Node {
    const el = document.createElement('div');
    el.className = 'cm-comment-marker';
    el.textContent = String(this.count);
    el.title = `${this.count} comment${this.count === 1 ? '' : 's'} on this line`;
    return el;
  }
}

const commentGutter = gutter({
  class: 'cm-comment-gutter',
  lineMarker(view, line) {
    const comments = view.state.field(commentsField);
    const lineNo = view.state.doc.lineAt(line.from).number;
    const count = comments.filter((c) => c.anchor?.line === lineNo).length;
    return count > 0 ? new CommentGutterMarker(count) : null;
  },
  lineMarkerChange: (update) =>
    update.transactions.some((tr) => tr.effects.some((e) => e.is(setCommentsEffect))),
  initialSpacer: () => new SpacerMarker(),
});

class SpacerMarker extends GutterMarker {
  toDOM(): Node {
    return document.createElement('div');
  }
}

const commentTheme = EditorView.theme({
  '.cm-comment-range': {
    backgroundColor: 'rgba(245, 158, 11, 0.16)',
  },
  '.cm-comment-gutter': {
    width: '22px',
  },
  '.cm-comment-marker': {
    width: '16px',
    height: '16px',
    borderRadius: '50%',
    backgroundColor: '#f59e0b',
    color: '#ffffff',
    fontSize: '10px',
    fontWeight: 600,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    margin: '1px auto',
  },
});

export default function SourceTab({ markdown, comments }: Props) {
  const viewRef = useRef<EditorView | null>(null);

  useEffect(() => {
    viewRef.current?.dispatch({ effects: setCommentsEffect.of(comments) });
  }, [comments]);

  const extensions = useMemo(
    () => [
      markdownLang(),
      EditorView.lineWrapping,
      commentsField,
      decorationsField,
      commentGutter,
      commentTheme,
    ],
    [],
  );

  return (
    <CodeMirror
      value={markdown}
      height="70vh"
      readOnly
      extensions={extensions}
      onCreateEditor={(view) => {
        viewRef.current = view;
        view.dispatch({ effects: setCommentsEffect.of(comments) });
      }}
      basicSetup={{ lineNumbers: true, foldGutter: false, highlightActiveLine: false }}
    />
  );
}
