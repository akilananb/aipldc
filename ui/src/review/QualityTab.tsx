import type { CSSProperties } from 'react';
import { ShieldQuestion } from 'lucide-react';
import { useQuery } from '@tanstack/react-query';
import { Box } from '@radix-ui/themes';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';
import { api } from '../api';
import EmptyState from '../components/EmptyState';
import RelativeTime from '../components/RelativeTime';

interface Props {
  id: string;
  label?: string;
}

export default function QualityTab({ id, label = 'Quality' }: Props) {
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
        title={`No ${label.toLowerCase()} report yet`}
        hint={
          label === 'Quality'
            ? 'The quality agent evaluates each draft version.'
            : 'Plan checks appear once the story is planned; build results replace them after the build loop.'
        }
      />
    );
  }

  const report = qualityQuery.data;
  const passed = report.verdict === 'passed';
  const ringStyle: CSSProperties & Record<string, string | number> = {
    '--score': report.score ?? 0,
    '--ring-color': passed ? 'var(--green)' : 'var(--red)',
  };
  const headline = passed ? 'Passed quality threshold' : report.verdict === 'failed' ? 'Below quality threshold' : report.verdict;

  return (
    <div>
      <div className="quality-summary">
        <div className="scorebox">
          <div className="ring" style={ringStyle}>
            <strong>
              {report.score ?? '—'}
              <small>/100</small>
            </strong>
          </div>
        </div>
        <div className="quality-copy">
          <h3>{headline}</h3>
          <p>
            Evaluated v{report.version} · <RelativeTime iso={report.createdAt} />
          </p>
          <div className="quality-chips">
            <span className={`pill ${passed ? 'pass' : 'fail'}`}>
              {label} · {report.verdict}
            </span>
          </div>
        </div>
      </div>
      <Box className="review-md">
        <Markdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeHighlight]}>{report.reportMd ?? '*No report content.*'}</Markdown>
      </Box>
    </div>
  );
}
