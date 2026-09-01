import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider, keepPreviousData } from '@tanstack/react-query';
import { Theme } from '@radix-ui/themes';
import { Toaster } from 'sonner';
import '@radix-ui/themes/styles.css';
import './styles.css';
import App from './App';
import { useAppearance } from './theme';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
      placeholderData: keepPreviousData,
    },
  },
});

function Root() {
  const appearance = useAppearance();
  return (
    <Theme appearance={appearance} accentColor="indigo" grayColor="slate" radius="large" scaling="95%">
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </QueryClientProvider>
      <Toaster position="bottom-right" richColors theme={appearance} />
    </Theme>
  );
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Root />
  </StrictMode>,
);
