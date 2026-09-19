import { Check, History, PackageOpen, ShieldCheck, X } from 'lucide-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Box, Button, Flex, Text, Tooltip } from '@radix-ui/themes';
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
  readOnly: boolean;
}

export default function ReleaseTab({ id, readOnly }: Props) {
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
    <div className="release-grid">
      {docs.map((doc) => {
        const canSign = identity.role === doc.checkerRole;
        return (
          <div key={doc.docId} className="release-card">
            <h3>
              {doc.title}{' '}
              <span className={`pill ${doc.signed ? 'pass' : 'review'}`}>{doc.signed ? 'Signed' : `Awaiting ${doc.checkerRole}`}</span>
            </h3>
            <div className="check-row">
              {doc.signed ? <Check size={14} /> : <X size={14} style={{ color: 'var(--dim)' }} />}
              {doc.checkerRole} signature
            </div>
            <Box className="review-md">
              <Collapsible maxHeight={300} defaultCollapsed>
                <Markdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeHighlight]}>{doc.content}</Markdown>
              </Collapsible>
            </Box>
            {!doc.signed && (
              <Flex direction="column" gap="1" mt="3">
                <Tooltip content={readOnly ? 'Read-only demo snapshot' : canSign ? '' : `Only ${doc.checkerRole} can sign this document`}>
                  <Button
                    disabled={readOnly || !canSign}
                    loading={signMutation.isPending && signMutation.variables?.docId === doc.docId}
                    onClick={() => signMutation.mutate({ docId: doc.docId, title: doc.title })}
                  >
                    Sign
                  </Button>
                </Tooltip>
                {readOnly && (
                  <Text size="1" color="gray">
                    Read-only demo snapshot
                  </Text>
                )}
              </Flex>
            )}
          </div>
        );
      })}
      <div className="release-card">
        <h3>Invalidation policy</h3>
        <p style={{ color: 'var(--muted)', fontSize: '.76rem' }}>
          Any request for changes clears all signatures so reviewers always sign the same immutable release pack.
        </p>
        <div className="check-row">
          <ShieldCheck size={14} />
          Deploy only after all four signatures
        </div>
        <div className="check-row">
          <History size={14} />
          Monitor agent evaluates once after deploy
        </div>
      </div>
    </div>
  );
}
