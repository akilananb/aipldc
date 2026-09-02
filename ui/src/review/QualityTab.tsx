import { ShieldQuestion } from 'lucide-react';
import { useQuery } from '@tanstack/react-query';
import { Badge, Box, Flex, Text } from '@radix-ui/themes';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';
import { api } from '../api';
import EmptyState from '../components/EmptyState';

interface Props {
  id: string;
}

export default function QualityTab({ id }: Props) {
  const qualityQuery = useQuery({
    queryKey: ['quality', id],
    queryFn: () => api.getQuality(id),
    refetchInterval: 5000,
    retry: false,
  });

  if (qualityQuery.isError || !qualityQuery.data) {
    return (
      <EmptyState
        icon={<ShieldQuestion size={28} />}
        title="No quality report yet"
        hint="The quality agent evaluates each draft version."
      />
    );
  }

  const report = qualityQuery.data;

  return (
    <Flex direction="column" gap="3">
      <Flex gap="3" align="center" wrap="wrap">
        <Badge color={report.verdict === 'passed' ? 'green' : 'red'}>Quality: {report.verdict}</Badge>
        <Text size="2" color="gray">
          Score: {report.score ?? '—'}/100 · v{report.version}
        </Text>
      </Flex>
      <Box className="review-md">
        <Markdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeHighlight]}>{report.reportMd ?? '*No report content.*'}</Markdown>
      </Box>
    </Flex>
  );
}
