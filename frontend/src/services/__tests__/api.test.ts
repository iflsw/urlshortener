import { describe, it, expect, vi, beforeEach } from 'vitest';
import { api, ApiRequestError } from '../api';

describe('api service', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
  });

  it('shorten sends POST to /shorten with correct body', async () => {
    const mockResponse = { alias: 'abc', fullUrl: 'https://ex.com', shortUrl: 'http://host/abc' };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      status: 201,
      json: async () => mockResponse,
    } as Response));

    const result = await api.shorten({ fullUrl: 'https://ex.com', customAlias: 'abc' });

    expect(fetch).toHaveBeenCalledWith('/shorten', expect.objectContaining({
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fullUrl: 'https://ex.com', customAlias: 'abc' }),
    }));
    expect(result).toEqual(mockResponse);
  });

  it('shorten throws with error message from API on failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      json: async () => ({ error: 'Alias already taken.' }),
    } as Response));

    await expect(api.shorten({ fullUrl: 'https://ex.com', customAlias: 'taken' }))
      .rejects.toThrow('Alias already taken.');
  });

  it('listPage requests the first page without a cursor', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ items: [], nextCursor: null }),
    } as Response));

    const page = await api.listPage({ size: 10 });

    expect(fetch).toHaveBeenCalledWith('/urls?size=10', expect.anything());
    expect(page).toEqual({ items: [], nextCursor: null });
  });

  it('listPage passes the cursor, URL-encoded', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ items: [], nextCursor: null }),
    } as Response));

    await api.listPage({ size: 10, cursor: 'a+b/c=' });

    expect(fetch).toHaveBeenCalledWith('/urls?size=10&cursor=a%2Bb%2Fc%3D', expect.anything());
  });

  it('listPage forwards the abort signal', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ items: [], nextCursor: null }),
    } as Response));
    const controller = new AbortController();

    await api.listPage({ size: 10 }, controller.signal);

    expect(fetch).toHaveBeenCalledWith(expect.any(String), { signal: controller.signal });
  });

  it('listPage rejects a response that is not a page envelope', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => [],
    } as Response));

    await expect(api.listPage({ size: 10 })).rejects.toThrow(/unexpected response/i);
  });

  it('errors carry the HTTP status', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      json: async () => ({ error: 'Invalid cursor.' }),
    } as Response));

    const error = await api.listPage({ size: 10, cursor: 'bad' }).catch((e) => e);

    expect(error).toBeInstanceOf(ApiRequestError);
    expect(error).toMatchObject({ status: 400, message: 'Invalid cursor.' });
  });

  it('delete sends DELETE to /{alias}', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      status: 204,
      json: async () => undefined,
    } as Response));

    await api.delete('my-alias');

    expect(fetch).toHaveBeenCalledWith('/my-alias', expect.objectContaining({ method: 'DELETE' }));
  });

  it('delete throws on 404', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      json: async () => ({ error: "No URL found for alias 'ghost'." }),
    } as Response));

    await expect(api.delete('ghost'))
      .rejects.toThrow("No URL found for alias 'ghost'.");
  });
});
