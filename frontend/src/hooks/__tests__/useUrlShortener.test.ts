import { renderHook, act, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { useUrlShortener, PAGE_SIZE } from '../useUrlShortener';
import { api, ApiRequestError } from '../../services/api';
import type { UrlListItem, UrlPage } from '../../types/api';

vi.mock('../../services/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../services/api')>();
  return {
    ...actual,
    api: { shorten: vi.fn(), listPage: vi.fn(), delete: vi.fn() },
  };
});

const listPage = vi.mocked(api.listPage);
const shorten = vi.mocked(api.shorten);
const remove = vi.mocked(api.delete);

const item = (alias: string): UrlListItem => ({
  alias,
  fullUrl: `https://example.com/${alias}`,
  shortUrl: `http://localhost:3000/${alias}`,
});
const page = (aliases: string[], nextCursor: string | null): UrlPage => ({
  items: aliases.map(item),
  nextCursor,
});

/** Last cursor requested from the API (undefined = first page). */
const lastCursor = () => listPage.mock.lastCall?.[0].cursor;

async function renderOnPage1(first: UrlPage = page(['a', 'b'], 'c1')) {
  listPage.mockResolvedValueOnce(first);
  const hook = renderHook(() => useUrlShortener());
  await waitFor(() => expect(hook.result.current.loading).toBe(false));
  return hook;
}

describe('useUrlShortener paging', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('loads the first page without a cursor', async () => {
    const { result } = await renderOnPage1();

    expect(listPage).toHaveBeenCalledWith({ size: PAGE_SIZE, cursor: undefined }, expect.any(AbortSignal));
    expect(result.current.urls.map((u) => u.alias)).toEqual(['a', 'b']);
    expect(result.current.page).toBe(1);
    expect(result.current.hasPrev).toBe(false);
    expect(result.current.hasNext).toBe(true);
  });

  it('next uses the latest nextCursor, prev goes back through the stack', async () => {
    const { result } = await renderOnPage1();

    listPage.mockResolvedValueOnce(page(['c', 'd'], 'c2'));
    act(() => result.current.nextPage());
    await waitFor(() => expect(result.current.urls[0].alias).toBe('c'));
    expect(lastCursor()).toBe('c1');
    expect(result.current.page).toBe(2);
    expect(result.current.hasPrev).toBe(true);

    listPage.mockResolvedValueOnce(page(['e'], null));
    act(() => result.current.nextPage());
    await waitFor(() => expect(result.current.urls[0].alias).toBe('e'));
    expect(lastCursor()).toBe('c2');
    expect(result.current.page).toBe(3);
    expect(result.current.hasNext).toBe(false);

    listPage.mockResolvedValueOnce(page(['c', 'd'], 'c2'));
    act(() => result.current.prevPage());
    await waitFor(() => expect(result.current.urls[0].alias).toBe('c'));
    expect(lastCursor()).toBe('c1');
    expect(result.current.page).toBe(2);
  });

  it('delete refetches the current page with the same cursor', async () => {
    const { result } = await renderOnPage1();
    listPage.mockResolvedValueOnce(page(['c', 'd'], 'c2'));
    act(() => result.current.nextPage());
    await waitFor(() => expect(result.current.page).toBe(2));

    remove.mockResolvedValueOnce(undefined);
    listPage.mockResolvedValueOnce(page(['d', 'e'], 'c3'));
    await act(() => result.current.deleteUrl('c'));
    await waitFor(() => expect(result.current.urls.map((u) => u.alias)).toEqual(['d', 'e']));

    expect(remove).toHaveBeenCalledWith('c');
    expect(lastCursor()).toBe('c1');
    expect(result.current.page).toBe(2);
  });

  it('steps back a page when a delete empties the current page', async () => {
    const { result } = await renderOnPage1();
    listPage.mockResolvedValueOnce(page(['c'], null));
    act(() => result.current.nextPage());
    await waitFor(() => expect(result.current.page).toBe(2));

    remove.mockResolvedValueOnce(undefined);
    listPage
      .mockResolvedValueOnce(page([], null)) // page 2 is now empty
      .mockResolvedValueOnce(page(['a', 'b'], null)); // back on page 1
    await act(() => result.current.deleteUrl('c'));

    await waitFor(() => expect(result.current.page).toBe(1));
    await waitFor(() => expect(result.current.urls.map((u) => u.alias)).toEqual(['a', 'b']));
    expect(lastCursor()).toBeUndefined();
    expect(result.current.hasNext).toBe(false);
  });

  it('shows the error and still refreshes when delete fails (e.g. already deleted)', async () => {
    const { result } = await renderOnPage1();

    remove.mockRejectedValueOnce(new ApiRequestError("No URL found for alias 'a'.", 404));
    listPage.mockResolvedValueOnce(page(['b'], null));
    await act(() => result.current.deleteUrl('a'));

    await waitFor(() => expect(result.current.urls.map((u) => u.alias)).toEqual(['b']));
    expect(result.current.error).toBe("No URL found for alias 'a'.");
  });

  it('a successful shorten resets to page 1', async () => {
    const { result } = await renderOnPage1();
    listPage.mockResolvedValueOnce(page(['c', 'd'], 'c2'));
    act(() => result.current.nextPage());
    await waitFor(() => expect(result.current.page).toBe(2));

    shorten.mockResolvedValueOnce(item('new'));
    listPage.mockResolvedValueOnce(page(['new', 'a'], 'c1'));
    await act(() => result.current.shorten('https://example.com/new'));

    await waitFor(() => expect(result.current.urls[0].alias).toBe('new'));
    expect(result.current.page).toBe(1);
    expect(lastCursor()).toBeUndefined();
    expect(result.current.lastCreated?.alias).toBe('new');
  });

  it('resets to page 1 and shows the error when the cursor is rejected', async () => {
    const { result } = await renderOnPage1();

    listPage
      .mockRejectedValueOnce(new ApiRequestError('Invalid cursor.', 400))
      .mockResolvedValueOnce(page(['a', 'b'], 'c1'));
    act(() => result.current.nextPage());

    await waitFor(() => expect(result.current.error).toBe('Invalid cursor.'));
    await waitFor(() => expect(lastCursor()).toBeUndefined());
    expect(result.current.page).toBe(1);
  });

  it('ignores a stale response that arrives after a newer request', async () => {
    const { result } = await renderOnPage1();

    let resolveSlow!: (p: UrlPage) => void;
    listPage
      .mockImplementationOnce(() => new Promise((r) => (resolveSlow = r))) // page 2, slow
      .mockResolvedValueOnce(page(['a', 'b'], 'c1')); // page 1 again, fast
    act(() => result.current.nextPage());
    act(() => result.current.prevPage());
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(async () => resolveSlow(page(['stale'], null)));

    expect(result.current.urls.map((u) => u.alias)).toEqual(['a', 'b']);
    expect(result.current.page).toBe(1);
  });
});
