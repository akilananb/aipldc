import { Link, Route, Routes } from 'react-router-dom';
import { Box, Flex, Heading, Text } from '@radix-ui/themes';
import ErrorBoundary from './components/ErrorBoundary';
import IdentitySwitcher from './components/IdentitySwitcher';
import ItemListPage from './routes/ItemListPage';
import ReviewPage from './routes/ReviewPage';

export default function App() {
  return (
    <Box>
      <header style={{ borderBottom: '1px solid var(--gray-a5)', padding: '12px 20px' }}>
        <Flex justify="between" align="center" gap="4">
          <Link to="/" style={{ textDecoration: 'none', color: 'inherit' }}>
            <Flex align="baseline" gap="3">
              <Heading size="5">AI PDLC</Heading>
              <Text size="2" color="gray">
                Story review
              </Text>
            </Flex>
          </Link>
          <IdentitySwitcher />
        </Flex>
      </header>
      <main style={{ padding: '20px' }}>
        <ErrorBoundary>
          <Routes>
            <Route path="/" element={<ItemListPage />} />
            <Route path="/items/:id" element={<ReviewPage />} />
          </Routes>
        </ErrorBoundary>
      </main>
    </Box>
  );
}
