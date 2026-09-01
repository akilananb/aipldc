import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Badge, Box, Callout, Heading, Table, Text } from '@radix-ui/themes';
import { api } from '../api';
import { stateBadgeColor } from '../ui-utils';

function formatDate(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString();
}

export default function ItemListPage() {
  const itemsQuery = useQuery({
    queryKey: ['items'],
    queryFn: api.listItems,
    refetchInterval: 2000,
  });

  return (
    <Box>
      <Heading size="4" mb="4">
        Work items
      </Heading>

      {itemsQuery.isLoading && <Text color="gray">Loading…</Text>}

      {itemsQuery.isError && (
        <Callout.Root color="red" style={{ maxWidth: 720 }}>
          <Callout.Text>Failed to load items: {String(itemsQuery.error)}</Callout.Text>
        </Callout.Root>
      )}

      {itemsQuery.isSuccess && (
        <Table.Root variant="surface">
          <Table.Header>
            <Table.Row>
              <Table.ColumnHeaderCell>ID</Table.ColumnHeaderCell>
              <Table.ColumnHeaderCell>Title</Table.ColumnHeaderCell>
              <Table.ColumnHeaderCell>Kind</Table.ColumnHeaderCell>
              <Table.ColumnHeaderCell>State</Table.ColumnHeaderCell>
              <Table.ColumnHeaderCell>Updated</Table.ColumnHeaderCell>
            </Table.Row>
          </Table.Header>
          <Table.Body>
            {itemsQuery.data.map((item) => (
              <Table.Row key={item.id}>
                <Table.RowHeaderCell>
                  <Link to={`/items/${encodeURIComponent(item.id)}`}>{item.id}</Link>
                </Table.RowHeaderCell>
                <Table.Cell>{item.title}</Table.Cell>
                <Table.Cell>{item.kind}</Table.Cell>
                <Table.Cell>
                  <Badge color={stateBadgeColor(item.canonicalState)}>{item.canonicalState}</Badge>
                </Table.Cell>
                <Table.Cell>
                  <Text size="2" color="gray">
                    {formatDate(item.updatedAt)}
                  </Text>
                </Table.Cell>
              </Table.Row>
            ))}
            {itemsQuery.data.length === 0 && (
              <Table.Row>
                <Table.Cell colSpan={5}>
                  <Text size="2" color="gray">
                    No work items yet. POST an item.created webhook to get started.
                  </Text>
                </Table.Cell>
              </Table.Row>
            )}
          </Table.Body>
        </Table.Root>
      )}
    </Box>
  );
}
