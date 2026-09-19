import { Flex, Select, Text } from '@radix-ui/themes';
import { useQueryClient } from '@tanstack/react-query';
import { DEMO_IDENTITIES, setIdentity, useIdentity } from '../identity';

function initials(label: string): string {
  return label
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0].toUpperCase())
    .join('');
}

/**
 * Dev-mode identity switcher. Selecting an identity sets the X-User / X-Role
 * headers used by src/api.ts for all subsequent requests and invalidates all
 * queries so they refetch under the new identity.
 */
export default function IdentitySwitcher() {
  const identity = useIdentity();
  const queryClient = useQueryClient();

  function onSelect(user: string) {
    const next = DEMO_IDENTITIES.find((i) => i.user === user);
    if (!next) return;
    setIdentity(next);
    void queryClient.invalidateQueries();
  }

  return (
    <div className="user">
      <Select.Root value={identity.user} onValueChange={onSelect} size="2">
        <Select.Trigger>
          <Flex align="center" gap="2">
            <span className="avatar">{initials(identity.label)}</span>
            <span>{identity.label}</span>
          </Flex>
        </Select.Trigger>
        <Select.Content>
          {DEMO_IDENTITIES.map((i) => (
            <Select.Item key={i.user} value={i.user}>
              <Flex align="center" gap="2">
                <span className="avatar">{initials(i.label)}</span>
                <Text>
                  {i.label} ({i.role})
                </Text>
              </Flex>
            </Select.Item>
          ))}
        </Select.Content>
      </Select.Root>
    </div>
  );
}
