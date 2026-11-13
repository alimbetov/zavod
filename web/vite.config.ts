import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The backend dev-local profile listens on loopback only. Vite is also bound
// to loopback: this proxy is NOT a production authentication mechanism.
export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: false } }
  }
});
