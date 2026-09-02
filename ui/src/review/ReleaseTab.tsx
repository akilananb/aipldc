import { PackageOpen } from 'lucide-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Badge, Box, Button, Card, Flex, Heading, Text, Tooltip } from '@radix-ui/themes';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';
import { toast } from 'sonner';
import { api, errorMessage } from '../api';
import { useIdentity } from '../identity';
import Collapsible from '../components/Collapsible';
import EmptyState from '../components/EmptyState';

interface Props {
  id: string;
}

export default function ReleaseTab({ id }: Props) {
  const identity = useIdentity();
  const queryClient = useQueryClient();

  const releaseQuery = useQuery({
    queryKey: ['release', id],
    queryFn: () => api.getRelease(id),
    enabled: !!id,
    refetchInterval: 5000,
    retry: false,
  });

  const signMutation = useMutation({
    mutationFn: ({ docId, title }: { docId: string; title: string }) =>
      api.signReleaseDoc(id, docId, '').then(() => title),
    onSuccess: (title) => {
      toast.success(`Signed ${title}`);
      void queryClient.invalidateQueries({ queryKey: ['release', id] });
      void queryClient.invalidateQueries({ queryKey: ['item', id] });
    },
    onError: (e) => toast.error(errorMessage(e)),
  });

  const docs = releaseQuery.data ?? [];

  if (releaseQuery.isError || (releaseQuery.isSuccess && docs.length === 0)) {
    return (
      <EmptyState
        icon={<PackageOpen size={28} />}
        title="No release pack yet"
        hint="Documents appear when the release agent drafts the pack."
      />
    );
  }

  if (releaseQuery.isLoading) {
    return (
      <Text size="2" color="gray">
        Loading release pack…
      </Text>
    );
  }

  return (
    <Flex direction="column" gap="3">
      {docs.map((doc) => {
        const canSign = identity.role === doc.checkerRole;
        return (
          <Card key={doc.docId} size="2">
            <Flex justify="between" align="center" mb="2" wrap="wrap" gap="2">
              <Heading size="3">{doc.title}</Heading>
              <Flex align="center" gap="2">
                <Badge color="gray" variant="soft">
                  {doc.checkerRole}
                </Badge>
                {doc.signed ? (
                  <Badge color="green">Signed</Badge>
                ) : (
                  <Badge color="amber">Awaiting {doc.checkerRole}</Badge>
                )}
              </Flex>
            </Flex>
            <Box className="review-md">
              <Collapsible maxHeight={300} defaultCollapsed>
                <Markdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeHighlight]}>{doc.content}</Markdown>
              </Collapsible>
            </Box>
            {!doc.signed && (
              <Tooltip content={canSign ? '' : `Only ${doc.checkerRole} can sign this document`}>
                <Button
                  mt="3"
                  disabled={!canSign}
                  loading={signMutation.isPending && signMutation.variables?.docId === doc.docId}
                  onClick={() => signMutation.mutate({ docId: doc.docId, title: doc.title })}
                >
                  Sign
                </Button>
              </Tooltip>
            )}
          </Card>
        );
      })}
    </Flex>
  );
}
