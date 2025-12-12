import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [react()],
  // root: 'public',  <-- Removed
  resolve: {
    alias: {
      '@src': path.resolve(__dirname, './src'),
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./vitest.setup.ts'],
    include: ['../src/**/*.test.ts'],
  },
  server: {
      host: true, // bind to 0.0.0.0
      port: 5173,
      strictPort: true,
  },
});

