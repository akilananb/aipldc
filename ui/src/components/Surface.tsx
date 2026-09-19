import type { ReactNode } from 'react';
import { Tabs, Tooltip } from '@radix-ui/themes';

export interface SurfaceTab {
  value: string;
  label: string;
  count?: number;
  disabled?: boolean;
  tooltip?: string;
}

interface Props {
  tabs: SurfaceTab[];
  value: string;
  onValueChange: (value: string) => void;
  inspector?: ReactNode;
  children: ReactNode;
}

export default function Surface({ tabs, value, onValueChange, inspector, children }: Props) {
  return (
    <section className="surface">
      <Tabs.Root value={value} onValueChange={onValueChange}>
        <Tabs.List>
          {tabs.map((tab) => {
            const trigger = (
              <Tabs.Trigger key={tab.value} value={tab.value} disabled={tab.disabled}>
                {tab.label}
                {tab.count != null && <span className="count">{tab.count}</span>}
              </Tabs.Trigger>
            );
            return tab.disabled && tab.tooltip ? (
              <Tooltip key={tab.value} content={tab.tooltip}>
                <span>{trigger}</span>
              </Tooltip>
            ) : (
              trigger
            );
          })}
        </Tabs.List>
        <div className="content-grid" style={inspector ? undefined : { gridTemplateColumns: 'minmax(0,1fr)' }}>
          <div className="pane">{children}</div>
          {inspector && <aside className="inspector">{inspector}</aside>}
        </div>
      </Tabs.Root>
    </section>
  );
}
