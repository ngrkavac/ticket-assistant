import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// Dev server only: /ask and /requests are proxied to the assistant, and the proxy adds the
// X-API-Key header from HELPDESK_API_KEY (shell env or ui/.env.local). The key never reaches the browser.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const target = env.ASSISTANT_URL || 'http://localhost:8080';
  const headers: Record<string, string> = env.HELPDESK_API_KEY ? { 'X-API-Key': env.HELPDESK_API_KEY } : {};
  const proxy = { target, changeOrigin: true, headers };
  return {
    plugins: [react()],
    server: { proxy: { '/ask': proxy, '/requests': proxy } },
  };
});
