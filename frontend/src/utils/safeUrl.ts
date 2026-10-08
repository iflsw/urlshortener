/**
 * True only for absolute http(s) URLs.
 * React does not block `javascript:` hrefs, and list data can predate server-side validation,
 * so anything rendered as a link must pass this check.
 */
export function isHttpUrl(value: string): boolean {
  try {
    const { protocol } = new URL(value);
    return protocol === 'http:' || protocol === 'https:';
  } catch {
    return false;
  }
}
