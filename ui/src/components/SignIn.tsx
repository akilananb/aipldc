import { Button, Callout, Flex, Heading, Text } from '@radix-ui/themes';
import { useQueryClient } from '@tanstack/react-query';
import { LogIn, LogOut } from 'lucide-react';
import { api, BASE_URL } from '../api';
import type { AuthState } from '../identity';

/**
 * Shown instead of the app when control-plane requires enterprise login and there is no usable
 * session: not signed in, signed in without a mapped PDLC role, or no login configured at all.
 * Login is a top-level navigation to control-plane, which runs the OIDC flow and redirects back.
 */
export default function SignIn({ auth }: { auth: AuthState }) {
  const queryClient = useQueryClient();
  const loginHref = auth.loginUrl ? `${BASE_URL}${auth.loginUrl}` : null;

  async function signOut() {
    await api.logout().catch(() => undefined);
    await queryClient.invalidateQueries({ queryKey: ['me'] });
  }

  return (
    <Flex align="center" justify="center" style={{ minHeight: '100vh', padding: '16px' }}>
      <Flex direction="column" gap="4" style={{ maxWidth: 420, width: '100%' }}>
        <Heading size="6">Sign in to eLoop.ai</Heading>
        {auth.error ? (
          <Callout.Root color="amber">
            <Callout.Text>{auth.error}. Ask an administrator to add you to a PDLC group.</Callout.Text>
          </Callout.Root>
        ) : auth.mode === 'none' ? (
          <Callout.Root color="red">
            <Callout.Text>
              No sign-in method is configured on the control plane. Configure an OIDC client, or enable dev
              headers for a local stack.
            </Callout.Text>
          </Callout.Root>
        ) : (
          <Text color="gray">Use your company account to continue.</Text>
        )}
        {auth.authenticated && (
          <Button size="3" variant="soft" onClick={() => void signOut()}>
            <LogOut size={16} /> Sign out and use another account
          </Button>
        )}
        {loginHref && !auth.error && (
          <Button size="3" asChild>
            <a href={loginHref}>
              <LogIn size={16} /> Sign in with SSO
            </a>
          </Button>
        )}
      </Flex>
    </Flex>
  );
}
