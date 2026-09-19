import { statePillVariant } from '../ui-utils';

interface Props {
  state: string;
  pulse?: boolean;
}

export default function StatusBadge({ state, pulse }: Props) {
  const variant = statePillVariant(state);
  return (
    <span className={`pill${variant ? ` ${variant}` : ''}`}>
      {pulse && <i className="dot pulse" />}
      {state}
    </span>
  );
}
