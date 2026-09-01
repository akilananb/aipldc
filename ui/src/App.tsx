import { Link, Route, Routes } from 'react-router-dom';
import { Moon, SearchX, Sun } from 'lucide-react';
import { Box, Flex, Heading, IconButton, Text } from '@radix-ui/themes';
import ErrorBoundary from './components/ErrorBoundary';
import EmptyState from './components/EmptyState';
import IdentitySwitcher from './components/IdentitySwitcher';
import { getAppearance, setAppearance, useAppearance } from './theme';
import ItemListPage from './routes/ItemListPage';
import ReviewPage from './routes/ReviewPage';

function ThemeToggle() {
  const appearance = useAppearance();
  return (
    <IconButton
      variant="ghost"
      aria-label="Toggle theme"
      onClick={() => setAppearance(getAppearance() === 'dark' ? 'light' : 'dark')}
    >
      {appearance === 'dark' ? <Moon size={16} /> : <Sun size={16} />}
    </IconButton>
  );
}

export default function App() {
  return (
    <Box>
      <header
        style={{
          position: 'sticky',
          top: 0,
          zIndex: 10,
          background: 'var(--color-panel-solid)',
          borderBottom: '1px solid var(--gray-a5)',
          padding: '12px 20px',
        }}
      >
        <Flex justify="between" align="center" gap="4">
          <Link to="/" style={{ textDecoration: 'none', color: 'inherit' }}>
            <Flex align="baseline" gap="3">
              <Heading size="5">EngLoop.ai</Heading>
              <Text size="2" color="gray">
                Product Development Lifecycle
              </Text>
            </Flex>
          </Link>
          <Flex align="center" gap="3">
            <ThemeToggle />
            <IdentitySwitcher />
          </Flex>
        </Flex>
      </header>
      <main>
        <Box className="app-container">
          <ErrorBoundary>
            <Routes>
              <Route path="/" element={<ItemListPage />} />
              <Route path="/items/:id" element={<ReviewPage />} />
              <Route
                path="*"
                element={
                  <EmptyState
                    icon={<SearchX size={28} />}
                    title="Page not found"
                    hint="Check the URL or go back to the item list."
                  />
                }
              />
            </Routes>
          </ErrorBoundary>
        </Box>
      </main>
    </Box>
  );
}
