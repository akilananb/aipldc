import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The API base URL is injected at build time via VITE_API_BASE_URL (see Dockerfile).
// In development the browser talks to control-plane directly at localhost:8081.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
  },
});
