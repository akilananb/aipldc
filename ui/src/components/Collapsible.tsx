import { useState, type ReactNode } from 'react';
import { Box, Button } from '@radix-ui/themes';

interface Props {
  children: ReactNode;
  maxHeight: number;
  defaultCollapsed: boolean;
}

export default function Collapsible({ children, maxHeight, defaultCollapsed }: Props) {
  const [collapsed, setCollapsed] = useState(defaultCollapsed);

  return (
    <Box>
      <Box
        style={{
          position: 'relative',
          maxHeight: collapsed ? maxHeight : undefined,
          overflow: collapsed ? 'hidden' : undefined,
        }}
      >
        {children}
        {collapsed && (
          <Box
            style={{
              position: 'absolute',
              bottom: 0,
              left: 0,
              right: 0,
              height: 48,
              background: 'linear-gradient(to bottom, transparent, var(--color-panel-solid))',
              pointerEvents: 'none',
            }}
          />
        )}
      </Box>
      <Button variant="ghost" size="1" mt="1" onClick={() => setCollapsed((c) => !c)}>
        {collapsed ? 'Show more' : 'Show less'}
      </Button>
    </Box>
  );
}
