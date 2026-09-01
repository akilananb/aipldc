import { Text } from '@radix-ui/themes';

interface Props {
  iso: string;
}

function relativize(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const deltaMs = Date.now() - d.getTime();
  const sec = Math.round(deltaMs / 1000);
  if (sec < 60) return 'just now';
  const min = Math.round(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.round(min / 60);
  if (hr < 24) return `${hr}h ago`;
  const day = Math.round(hr / 24);
  if (day < 7) return `${day}d ago`;
  return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

export default function RelativeTime({ iso }: Props) {
  const d = new Date(iso);
  const full = Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
  return (
    <Text size="2" color="gray" title={full}>
      {relativize(iso)}
    </Text>
  );
}
