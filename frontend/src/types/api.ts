export interface ShortenUrlRequest {
  fullUrl: string;
  customAlias?: string;
}

export interface ShortenUrlResponse {
  alias: string;
  fullUrl: string;
  shortUrl: string;
}

export interface UrlListItem {
  alias: string;
  fullUrl: string;
  shortUrl: string;
}

/** GET /urls response: newest first. nextCursor is opaque and null on the last page. */
export interface UrlPage {
  items: UrlListItem[];
  nextCursor: string | null;
}

export interface ListUrlsParams {
  size: number;
  cursor?: string;
}

export interface ApiError {
  error: string;
}
