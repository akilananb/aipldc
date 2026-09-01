import type { ReactNode } from 'react';
import { Card, Heading } from '@radix-ui/themes';

interface Props {
  title?: string;
  children: ReactNode;
}

export default function Panel({ title, children }: Props) {
  return (
    <Card size="2">
      {title && (
        <Heading size="3" mb="3">
          {title}
        </Heading>
      )}
      {children}
    </Card>
  );
}
