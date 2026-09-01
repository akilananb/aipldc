import { Copy } from 'lucide-react';
import { Code, Flex, IconButton, Tooltip } from '@radix-ui/themes';
import { toast } from 'sonner';

interface Props {
  hash: string;
}

export default function CopyHash({ hash }: Props) {
  const short = hash.slice(0, 12);

  async function copy() {
    await navigator.clipboard.writeText(hash);
    toast.success('Hash copied');
  }

  return (
    <Flex align="center" gap="1">
      <Tooltip content={`sha256:${hash}`}>
        <Code size="1">sha256:{short}</Code>
      </Tooltip>
      <IconButton variant="ghost" size="1" aria-label="Copy hash" onClick={copy}>
        <Copy size={13} />
      </IconButton>
    </Flex>
  );
}
