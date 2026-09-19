import { Route, Routes } from 'react-router-dom';
import { SearchX } from 'lucide-react';
import ErrorBoundary from './components/ErrorBoundary';
import EmptyState from './components/EmptyState';
import AppShell from './components/AppShell';
import ItemListPage from './routes/ItemListPage';
import ReviewPage from './routes/ReviewPage';

export default function App() {
  return (
    <AppShell>
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
    </AppShell>
  );
}
