import type { ReactNode } from 'react';
import { Flex, Heading, Text } from '@radix-ui/themes';

interface Props {
  icon: ReactNode;
  title: string;
  hint?: string;
}

export default function EmptyState({ icon, title, hint }: Props) {
  return (
    <Flex direction="column" align="center" gap="2" py="6" style={{ color: 'var(--gray-9)' }}>
      <div style={{ width: 28, height: 28, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        {icon}
      </div>
      <Heading size="3" color="gray">
        {title}
      </Heading>
      {hint && (
        <Text size="2" color="gray" align="center">
          {hint}
        </Text>
      )}
    </Flex>
  );
}
