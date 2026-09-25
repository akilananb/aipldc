import { Route, Routes } from 'react-router-dom';
import { SearchX } from 'lucide-react';
import ErrorBoundary from './components/ErrorBoundary';
import EmptyState from './components/EmptyState';
import AppShell from './components/AppShell';
import ItemListPage from './routes/ItemListPage';
import AgentsPage from './routes/AgentsPage';
import ProjectsPage from './routes/ProjectsPage';
import ReviewPage from './routes/ReviewPage';
import StudioPage from './studio/StudioPage';
import AgentEditorPage from './studio/AgentEditorPage';
import ToolEditorPage from './studio/ToolEditorPage';

export default function App() {
  return (
    <AppShell>
      <ErrorBoundary>
        <Routes>
          <Route path="/" element={<ItemListPage />} />
          <Route path="/items/:id" element={<ReviewPage />} />
          <Route path="/agents" element={<AgentsPage />} />
          <Route path="/projects" element={<ProjectsPage />} />
          <Route path="/studio" element={<StudioPage />} />
          <Route path="/studio/:ws/agents/:agentId" element={<AgentEditorPage />} />
          <Route path="/studio/:ws/tools/:toolId" element={<ToolEditorPage />} />
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
