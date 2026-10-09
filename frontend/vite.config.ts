// Adds the Vitest `test` block to Vite's config type. Without it, type-checking this file
// (tsconfig.node.json, e.g. in the IDE) fails with TS2769 on `test`.
/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * Object form on purpose: Vite's string shorthand ('/x': 'http://...') sets changeOrigin: true,
 * which rewrites the Host header to localhost:8080, so the API would build short URLs on port 8080.
 * Keeping the browser's Host (localhost:3000) matches nginx (proxy_set_header Host $http_host).
 */
const apiProxy = { target: 'http://localhost:8080', changeOrigin: false };

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    // Dev-only equivalent of frontend/nginx.conf: send API calls to the backend, leave the rest to Vite.
    proxy: {
      '/shorten': apiProxy,
      '/urls': apiProxy,
      // Alias paths (GET redirect, DELETE): one segment, same rule as nginx and alias validation.
      // A key starting with '^' is a regular expression, matched against the path plus query string.
      // Vite's own paths never match: they contain '/', '.', '@' or are just '/'.
      '^/[A-Za-z0-9-]{2,64}(\\?.*)?$': apiProxy,
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
  },
});
