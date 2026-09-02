import { ShieldCheck, ShieldQuestion, ShieldX } from 'lucide-react';
import { Tooltip } from '@radix-ui/themes';

interface Props {
  verdict: string | null | undefined;
}

export default function QualityIcon({ verdict }: Props) {
  if (verdict === 'passed') {
    return (
      <Tooltip content="Quality passed">
        <ShieldCheck size={16} color="var(--green-9)" />
      </Tooltip>
    );
  }
  if (verdict === 'failed') {
    return (
      <Tooltip content="Quality failed">
        <ShieldX size={16} color="var(--red-9)" />
      </Tooltip>
    );
  }
  return (
    <Tooltip content="No quality report">
      <ShieldQuestion size={16} color="var(--gray-8)" />
    </Tooltip>
  );
}
