import { Button, Flex, Text } from '@radix-ui/themes';
import { useQueryClient } from '@tanstack/react-query';
import { LogOut } from 'lucide-react';
import { api, errorMessage } from '../api';
import { useAuth } from '../identity';

function initials(user: string): string {
  const name = user.split('@')[0];
  return name
    .split(/[.\-_\s]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0].toUpperCase())
    .join('');
}

/** Signed-in enterprise user (OIDC mode): who you are, your mapped PDLC role, and Log out. */
export default function UserMenu() {
  const auth = useAuth();
  const queryClient = useQueryClient();

  async function logout() {
    try {
      await api.logout();
    } catch (e) {
      console.warn('logout failed', errorMessage(e));
    }
    await queryClient.invalidateQueries({ queryKey: ['me'] });
  }

  if (!auth.user) return null;
  return (
    <div className="user">
      <span className="avatar">{initials(auth.user)}</span>
      <Flex direction="column" style={{ minWidth: 0, flex: 1 }}>
        <Text size="1" truncate>
          {auth.user}
        </Text>
        <Text size="1" color="gray">
          {auth.role ?? 'No PDLC role'}
        </Text>
      </Flex>
      <Button size="1" variant="ghost" onClick={() => void logout()} aria-label="Log out">
        <LogOut size={14} />
      </Button>
    </div>
  );
}
