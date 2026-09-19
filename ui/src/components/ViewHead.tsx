import type { ReactNode } from 'react';

interface Props {
  title: string;
  sub?: string;
  aside?: ReactNode;
}

export default function ViewHead({ title, sub, aside }: Props) {
  return (
    <div className="view-head">
      <div>
        <h2>{title}</h2>
        {sub && <p>{sub}</p>}
      </div>
      {aside}
    </div>
  );
}
