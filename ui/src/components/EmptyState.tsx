import type { ReactNode } from 'react';

interface Props {
  icon: ReactNode;
  title: string;
  hint?: string;
  action?: ReactNode;
}

export default function EmptyState({ icon, title, hint, action }: Props) {
  return (
    <div className="empty">
      <div>
        <span className="empty-icon">{icon}</span>
        <h3>{title}</h3>
        {hint && <p>{hint}</p>}
        {action}
      </div>
    </div>
  );
}
