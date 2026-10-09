import type {
  ListUrlsParams,
  ShortenUrlRequest,
  ShortenUrlResponse,
  UrlPage,
} from '../types/api';

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? '';

/** An API failure, with the HTTP status so callers can react to specific cases (e.g. 400 on a cursor). */
export class ApiRequestError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = 'ApiRequestError';
  }
}

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let message = `Request failed (${res.status})`;
    try {
      const body = await res.json();
      if (typeof body?.error === 'string') message = body.error;
    } catch {
      // ignore parse errors
    }
    throw new ApiRequestError(message, res.status);
  }
  // 204 No Content has no body
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

function isUrlPage(body: unknown): body is UrlPage {
  const page = body as UrlPage | null;
  return (
    typeof page === 'object' &&
    page !== null &&
    Array.isArray(page.items) &&
    (page.nextCursor === null || typeof page.nextCursor === 'string')
  );
}

export const api = {
  shorten(request: ShortenUrlRequest): Promise<ShortenUrlResponse> {
    return fetch(`${API_BASE}/shorten`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    }).then((r) => handleResponse<ShortenUrlResponse>(r));
  },

  /** One page of URLs, newest first. Omit the cursor for the first page. */
  async listPage({ size, cursor }: ListUrlsParams, signal?: AbortSignal): Promise<UrlPage> {
    const query = new URLSearchParams({ size: String(size) });
    if (cursor) query.set('cursor', cursor);
    const body = await fetch(`${API_BASE}/urls?${query}`, { signal }).then((r) =>
      handleResponse<unknown>(r),
    );
    if (!isUrlPage(body)) {
      throw new Error('Unexpected response from the server.');
    }
    return body;
  },

  delete(alias: string): Promise<void> {
    return fetch(`${API_BASE}/${encodeURIComponent(alias)}`, {
      method: 'DELETE',
    }).then((r) => handleResponse<void>(r));
  },
};
