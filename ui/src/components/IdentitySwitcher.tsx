import { Flex, Select, Text } from '@radix-ui/themes';
import { useQueryClient } from '@tanstack/react-query';
import { DEMO_IDENTITIES, setIdentity, useIdentity } from '../identity';

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
    <Flex align="center" gap="2">
      <Text size="2" color="gray">
        Viewing as
      </Text>
      <Select.Root value={identity.user} onValueChange={onSelect} size="2">
        <Select.Trigger style={{ minWidth: 190 }} />
        <Select.Content>
          {DEMO_IDENTITIES.map((i) => (
            <Select.Item key={i.user} value={i.user}>
              {i.label} · {i.role}
            </Select.Item>
          ))}
        </Select.Content>
      </Select.Root>
    </Flex>
  );
}
