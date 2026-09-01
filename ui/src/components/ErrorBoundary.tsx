import { Component, type ReactNode } from 'react';
import { AlertTriangle } from 'lucide-react';
import { Box, Button, Callout, Heading } from '@radix-ui/themes';

interface Props {
  children: ReactNode;
}

interface State {
  error: Error | null;
}

/**
 * Top-level render guard so a query/render failure surfaces a message
 * instead of a blank white screen.
 */
export default class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  render() {
    if (this.state.error) {
      return (
        <Box p="6">
          <Callout.Root color="red" style={{ maxWidth: 720 }}>
            <Callout.Icon>
              <AlertTriangle size={15} />
            </Callout.Icon>
            <Callout.Text>
              <Heading size="3" mb="1">
                Something went wrong rendering this view.
              </Heading>
              {String(this.state.error)}
            </Callout.Text>
          </Callout.Root>
          <Button mt="4" onClick={() => window.location.reload()}>
            Reload
          </Button>
        </Box>
      );
    }
    return this.props.children;
  }
}
