import ReactDiffViewer, { DiffMethod } from 'react-diff-viewer-continued';
import { Text } from '@radix-ui/themes';

interface Props {
  oldMarkdown: string;
  newMarkdown: string;
  oldLoading: boolean;
  oldError: boolean;
}

export default function DiffTab({ oldMarkdown, newMarkdown, oldLoading, oldError }: Props) {
  if (oldLoading) return <Text color="gray">Loading previous version…</Text>;
  if (oldError) return <Text color="red">Failed to load the previous version.</Text>;

  return (
    <div style={{ fontSize: 13 }}>
      <ReactDiffViewer
        oldValue={oldMarkdown}
        newValue={newMarkdown}
        splitView
        showDiffOnly={false}
        useDarkTheme={false}
        compareMethod={DiffMethod.WORDS}
      />
    </div>
  );
}
