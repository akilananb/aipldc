import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Box, Flex, Heading, Text } from '@radix-ui/themes';

interface Props {
  backTo?: { to: string; label: string };
  title: string;
  badges?: ReactNode;
  meta?: ReactNode;
  actions?: ReactNode;
}

export default function PageHeader({ backTo, title, badges, meta, actions }: Props) {
  return (
    <Flex justify="between" align="start" gap="4" mb="4" wrap="wrap">
      <Box>
        {backTo && (
          <Text size="2" mb="1" as="div">
            <Link to={backTo.to}>← {backTo.label}</Link>
          </Text>
        )}
        <Heading size="5">{title}</Heading>
        {(badges || meta) && (
          <Flex gap="2" mt="2" align="center" wrap="wrap">
            {badges}
            {meta && (
              <Text size="2" color="gray">
                {meta}
              </Text>
            )}
          </Flex>
        )}
      </Box>
      {actions && <Flex gap="2">{actions}</Flex>}
    </Flex>
  );
}
