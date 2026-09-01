// Small shared UI helpers.

export type BadgeColor = 'gray' | 'green' | 'amber' | 'red' | 'blue' | 'orange' | 'violet';

export function stateBadgeColor(state: string): BadgeColor {
  switch (state) {
    case 'approved':
      return 'green';
    case 'awaiting-G1':
    case 'awaiting-G2':
    case 'awaiting-G3':
    case 'needs-clarification':
      return 'amber';
    case 'stale':
      return 'red';
    case 'done':
      return 'violet';
    default:
      return 'gray';
  }
}

export function intentBadgeColor(intent: string): BadgeColor {
  switch (intent) {
    case 'change':
      return 'orange';
    case 'question':
      return 'blue';
    default:
      return 'gray';
  }
}
