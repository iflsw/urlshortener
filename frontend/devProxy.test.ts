// @vitest-environment node
// Lives outside src/ on purpose: it imports vite.config.ts, which belongs to tsconfig.node.json.
// Importing it from src/ (the app tsconfig) fails type-checking with TS6305, so this file is included in tsconfig.node.json instead.

import { describe, expect, it } from 'vitest';
import config from './vite.config';

/**
 * Mirrors how the Vite dev server picks a proxy rule for a request URL (path + query):
 * keys starting with '^' are regular expressions, other keys are path prefixes.
 */
type ProxyRule = string | { target?: string; changeOrigin?: boolean };

function proxyRuleFor(url: string): ProxyRule | undefined {
  const proxy = (config.server?.proxy ?? {}) as Record<string, ProxyRule>;
  const key = Object.keys(proxy).find((rule) =>
    rule.startsWith('^') ? new RegExp(rule).test(url) : url.startsWith(rule),
  );
  return key === undefined ? undefined : proxy[key];
}

function proxyTargetFor(url: string): string | undefined {
  const rule = proxyRuleFor(url);
  return typeof rule === 'string' ? rule : rule?.target;
}

const API = 'http://localhost:8080';

describe('dev server proxy', () => {
  it.each(['/shorten', '/urls', '/urls?size=20&cursor=abc'])('sends API route %s to the backend', (url) => {
    expect(proxyTargetFor(url)).toBe(API);
  });

  it.each(['/gh', '/my-alias', '/aB3xY9z', '/abc?utm=1', `/${'a'.repeat(64)}`])(
    'sends alias path %s to the backend (redirect and DELETE)',
    (url) => {
      expect(proxyTargetFor(url)).toBe(API);
    },
  );

  it.each([
    '/',                                     // the app itself
    '/?token=abc',                           // Vite HMR websocket
    '/index.html',
    '/favicon.ico',
    '/src/main.tsx',                         // source modules served by Vite
    '/@vite/client',
    '/@react-refresh',
    '/node_modules/.vite/deps/react.js?v=1',
    '/a',                                    // too short to be an alias
    `/${'a'.repeat(65)}`,                    // too long to be an alias
  ])('leaves %s to Vite', (url) => {
    expect(proxyTargetFor(url)).toBeUndefined();
  });

  it.each(['/shorten', '/urls', '/gh'])('keeps the browser Host header for %s, so short URLs use port 3000', (url) => {
    // Vite's string shorthand implies changeOrigin: true (Host becomes localhost:8080).
    const rule = proxyRuleFor(url);
    expect(typeof rule).toBe('object');
    expect(rule).toMatchObject({ changeOrigin: false });
  });
});
