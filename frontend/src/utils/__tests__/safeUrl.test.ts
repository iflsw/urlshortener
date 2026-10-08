import { describe, it, expect } from 'vitest';
import { isHttpUrl } from '../safeUrl';

describe('isHttpUrl', () => {
  it.each(['http://example.com', 'https://example.com/a?b=c#d', 'HTTPS://EXAMPLE.COM'])(
    'accepts %s',
    (value) => expect(isHttpUrl(value)).toBe(true),
  );

  it.each([
    'javascript:alert(1)',
    ' javascript:alert(1)',
    'JaVaScRiPt:alert(1)',
    'data:text/html,<script>alert(1)</script>',
    'ftp://example.com',
    'not a url',
    '',
  ])('rejects %s', (value) => expect(isHttpUrl(value)).toBe(false));
});
