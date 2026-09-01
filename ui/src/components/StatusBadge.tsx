import { Badge } from '@radix-ui/themes';
import { stateBadgeColor } from '../ui-utils';

interface Props {
  state: string;
}

export default function StatusBadge({ state }: Props) {
  return <Badge color={stateBadgeColor(state)}>{state}</Badge>;
}
