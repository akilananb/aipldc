import { Copy } from 'lucide-react';
import { Box, Code, Flex, IconButton, Text } from '@radix-ui/themes';
import { toast } from 'sonner';
import { BASE_URL } from '../api';
import type { Project } from '../types';

interface Props {
  project: Project;
}

function CommandLine({ command }: { command: string }) {
  async function copy() {
    await navigator.clipboard.writeText(command);
    toast.success('Command copied');
  }
  return (
    <Flex align="center" gap="1">
      <Code size="1" style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
        {command}
      </Code>
      <IconButton variant="ghost" size="1" aria-label="Copy command" onClick={copy}>
        <Copy size={13} />
      </IconButton>
    </Flex>
  );
}

/** "Run a build agent locally" guide for the {@code /projects} page — copy-paste commands filled
 * from one project's repos, so a human can start a build-worker without reading build-worker's own
 * README. Each repo's override is listed under {@code TARGET_REPO_OVERRIDES}; the worker checks out
 * a task's repo from the project config when no override matches. */
export default function AgentSetupGuide({ project }: Props) {
  const claimCommand = `PDLC_API_URL=${BASE_URL} BUILD_FILTER_PROFILE=${project.id} BUILD_AGENT_NAME=<your-name> node dist/worker.js`;
  const overridesCommand = `TARGET_REPO_OVERRIDES=${project.repos.map((r) => `${r.id}=${r.url}`).join(',')}`;

  return (
    <Box mt="6">
      <Text as="p" size="3" weight="medium" mb="2">
        Run a build agent locally
      </Text>
      <ol style={{ paddingLeft: '1.25rem', display: 'flex', flexDirection: 'column', gap: '0.6rem', margin: 0 }}>
        <li>
          <Text as="p" size="2" color="gray">
            Prerequisites: Node 22, git, and omp on PATH already able to run <Code size="1">omp acp</Code> (the coding
            agent uses omp's own model credentials, not PDLC_LLM_API_KEY).
          </Text>
        </li>
        <li>
          <CommandLine command="cd build-worker && npm ci && npm run build" />
        </li>
        <li>
          <CommandLine command={claimCommand} />
          <Text as="p" size="1" color="gray" mt="1">
            Leave TARGET_REPO_OVERRIDES unset — the worker checks out each task's repo from the project config. Set it
            only to point a repo id at a local checkout or a different remote; a mismatch against the project config is
            flagged on the Agents page.
          </Text>
          <CommandLine command={overridesCommand} />
        </li>
        <li>
          <Text as="p" size="2" color="gray">
            Your agent appears on the Agents page within ~5 seconds; a story waiting on a build-worker resumes
            automatically.
          </Text>
        </li>
      </ol>
    </Box>
  );
}
