import ReactDiffViewer, { DiffMethod } from 'react-diff-viewer-continued';
import { Box, Skeleton } from '@radix-ui/themes';
import ErrorCallout from '../components/ErrorCallout';
import { useAppearance } from '../theme';

interface Props {
  oldMarkdown: string;
  newMarkdown: string;
  oldLoading: boolean;
  oldError: boolean;
}

export default function DiffTab({ oldMarkdown, newMarkdown, oldLoading, oldError }: Props) {
  const appearance = useAppearance();

  if (oldLoading) return <Skeleton height="240px" />;
  if (oldError) return <ErrorCallout title="Failed to load the previous version" error="see server logs" />;

  return (
    <Box style={{ fontSize: 'var(--font-size-1)' }}>
      <ReactDiffViewer
        oldValue={oldMarkdown}
        newValue={newMarkdown}
        splitView
        showDiffOnly={false}
        useDarkTheme={appearance === 'dark'}
        compareMethod={DiffMethod.WORDS}
      />
    </Box>
  );
}
