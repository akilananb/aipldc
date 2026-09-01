import { AlertTriangle } from 'lucide-react';
import { Callout } from '@radix-ui/themes';
import { errorMessage } from '../api';

interface Props {
  title: string;
  error: unknown;
}

export default function ErrorCallout({ title, error }: Props) {
  return (
    <Callout.Root color="red" style={{ maxWidth: 720 }}>
      <Callout.Icon>
        <AlertTriangle size={15} />
      </Callout.Icon>
      <Callout.Text>
        {title}: {errorMessage(error)}
      </Callout.Text>
    </Callout.Root>
  );
}
