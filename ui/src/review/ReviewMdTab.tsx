import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';
import { Box, Callout, Text } from '@radix-ui/themes';

interface Props {
  loading: boolean;
  error: string | null;
  content: string | null;
}

export default function ReviewMdTab({ loading, error, content }: Props) {
  if (loading) return <Text color="gray">Loading review.md…</Text>;
  if (error) {
    return (
      <Callout.Root color="red" style={{ maxWidth: 720 }}>
        <Callout.Text>Failed to load review.md: {error}</Callout.Text>
      </Callout.Root>
    );
  }
  return (
    <Box className="review-md">
      <Markdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeHighlight]}>{content ?? '*No review.md yet.*'}</Markdown>
    </Box>
  );
}
