import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// Dev server only: /ask, /requests, /customers and /questions are proxied to the assistant. There is
// no oauth2-proxy here, so the UI shows no user menu. Run the assistant with the `local` profile;
// if it has a HELPDESK_API_KEY, set the same value here (shell env or ui/.env.local) and the proxy
// adds it as X-API-Key. Signing in through Keycloak needs the compose stack (README).
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const target = env.ASSISTANT_URL || 'http://localhost:8080';
  const headers: Record<string, string> = env.HELPDESK_API_KEY ? { 'X-API-Key': env.HELPDESK_API_KEY } : {};
  const proxy = { target, changeOrigin: true, headers };
  return {
    plugins: [react()],
    server: { proxy: { '/ask': proxy, '/requests': proxy, '/customers': proxy, '/questions': proxy } },
  };
});
