import type { ReactNode } from 'react';

interface Props {
  title: string;
  subtitle?: ReactNode;
  badges?: ReactNode;
  meta?: ReactNode;
  actions?: ReactNode;
}

/** Renders a list of meta facts, each as its own stamped `.meta-tag` chip — the traveler-card
 * idiom (routing-card facts sit in their own boxes, not run together as prose). Entries are
 * usually plain text but may be nodes (e.g. the project name as a link to its config page). */
export function MetaItems({ items }: { items: ReactNode[] }) {
  return (
    <>
      {items.map((text, i) => (
        <span key={i} className="meta-tag">
          {text}
        </span>
      ))}
    </>
  );
}

export default function PageHeader({ title, subtitle, badges, meta, actions }: Props) {
  return (
    <section className="feature-head">
      <div>
        <div className="feature-meta">
          {badges}
          {meta}
        </div>
        <h1>{title}</h1>
        {subtitle && <p className="subtitle">{subtitle}</p>}
      </div>
      {actions && <div className="top-actions">{actions}</div>}
    </section>
  );
}
