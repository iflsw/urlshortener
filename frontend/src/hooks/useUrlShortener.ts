import { useState, useCallback, useEffect } from 'react';
import { api, ApiRequestError } from '../services/api';
import type { ShortenUrlResponse, UrlListItem } from '../types/api';

/** Sent explicitly so the UI does not depend on the backend default (20). */
export const PAGE_SIZE = 10;

/** Cursor for each visited page; null is the first page. The top of the stack is the current page. */
type CursorStack = (string | null)[];

interface UseUrlShortenerReturn {
  urls: UrlListItem[];
  page: number;
  hasPrev: boolean;
  hasNext: boolean;
  loading: boolean;
  error: string | null;
  lastCreated: ShortenUrlResponse | null;
  /** Resolves true if the URL was created; false if it failed (the message is in `error`). */
  shorten: (fullUrl: string, customAlias?: string) => Promise<boolean>;
  deleteUrl: (alias: string) => Promise<void>;
  nextPage: () => void;
  prevPage: () => void;
  clearLastCreated: () => void;
}

const messageOf = (e: unknown, fallback: string) => (e instanceof Error ? e.message : fallback);

/**
 * URL list state with cursor paging (newest first).
 *
 * Every change to the cursor stack triggers a fetch of the page on top of it, so navigation,
 * refresh after delete, and reset after shorten are all expressed as stack updates:
 * - next: push the nextCursor of the page currently shown (never a cursor stored earlier, so
 *   moving forward cannot skip rows after inserts or deletes)
 * - prev: pop
 * - refresh: same stack, new array identity
 * - reset: [null]
 */
export function useUrlShortener(): UseUrlShortenerReturn {
  const [cursors, setCursors] = useState<CursorStack>([null]);
  const [urls, setUrls] = useState<UrlListItem[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [lastCreated, setLastCreated] = useState<ShortenUrlResponse | null>(null);

  useEffect(() => {
    // Aborting on every stack change means a slow, superseded response can never overwrite a newer page.
    const controller = new AbortController();
    const cursor = cursors[cursors.length - 1] ?? undefined;
    const onFirstPage = cursors.length === 1;

    setLoading(true);
    api
      .listPage({ size: PAGE_SIZE, cursor }, controller.signal)
      .then((result) => {
        if (controller.signal.aborted) return;
        if (result.items.length === 0 && !onFirstPage) {
          // The page emptied (e.g. its last row was deleted): show the previous page instead.
          setCursors((prev) => prev.slice(0, -1));
          return;
        }
        setUrls(result.items);
        setNextCursor(result.nextCursor);
        setLoading(false);
      })
      .catch((e: unknown) => {
        if (controller.signal.aborted) return;
        setError(messageOf(e, 'Failed to load URLs.'));
        if (e instanceof ApiRequestError && e.status === 400 && !onFirstPage) {
          // Rejected cursor: start again from the newest URLs.
          setCursors([null]);
          return;
        }
        setLoading(false);
      });

    return () => controller.abort();
  }, [cursors]);

  const refresh = useCallback(() => setCursors((prev) => [...prev]), []);
  const resetToFirstPage = useCallback(() => setCursors([null]), []);

  const nextPage = useCallback(() => {
    if (nextCursor === null) return;
    setError(null);
    setCursors((prev) => [...prev, nextCursor]);
  }, [nextCursor]);

  const prevPage = useCallback(() => {
    setError(null);
    setCursors((prev) => (prev.length > 1 ? prev.slice(0, -1) : prev));
  }, []);

  const shorten = useCallback(
    async (fullUrl: string, customAlias?: string): Promise<boolean> => {
      setError(null);
      setLastCreated(null);
      try {
        const result = await api.shorten({ fullUrl, customAlias: customAlias || undefined });
        setLastCreated(result);
        // The new URL is the newest, so it is on page 1.
        resetToFirstPage();
        return true;
      } catch (e) {
        setError(messageOf(e, 'Failed to shorten URL.'));
        return false;
      }
    },
    [resetToFirstPage],
  );

  const deleteUrl = useCallback(
    async (alias: string) => {
      setError(null);
      try {
        await api.delete(alias);
      } catch (e) {
        setError(messageOf(e, 'Failed to delete URL.'));
      }
      // Refresh either way: on success a row from the next page moves up; on 404 the row was
      // already gone, so the list was stale.
      refresh();
    },
    [refresh],
  );

  const clearLastCreated = useCallback(() => setLastCreated(null), []);

  return {
    urls,
    page: cursors.length,
    hasPrev: cursors.length > 1,
    hasNext: nextCursor !== null,
    loading,
    error,
    lastCreated,
    shorten,
    deleteUrl,
    nextPage,
    prevPage,
    clearLastCreated,
  };
}
