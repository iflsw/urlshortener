import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import App from '../App';
import { api } from '../services/api';

vi.mock('../services/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/api')>();
  return {
    ...actual,
    api: { shorten: vi.fn(), listPage: vi.fn(), delete: vi.fn() },
  };
});

const listPage = vi.mocked(api.listPage);
const shorten = vi.mocked(api.shorten);

const created = { alias: 'abc1234', fullUrl: 'https://example.com/', shortUrl: 'http://localhost:3000/abc1234' };

const successBanner = () => screen.queryByText('Your short URL');
const submit = () => fireEvent.click(screen.getByRole('button', { name: /shorten url/i }));

describe('App success banner', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    listPage.mockResolvedValue({ items: [], nextCursor: null });
  });

  async function createOne() {
    render(<App />);
    await screen.findByText(/no shortened urls yet/i);
    shorten.mockResolvedValueOnce(created);
    await userEvent.type(screen.getByLabelText(/long url/i), 'https://example.com');
    submit();
    await waitFor(() => expect(successBanner()).toBeInTheDocument());
  }

  it('shows the short URL after a successful submission', async () => {
    await createOne();

    expect(screen.getAllByText('http://localhost:3000/abc1234').length).toBeGreaterThan(0);
  });

  it('removes the previous success banner when the next submission fails validation', async () => {
    await createOne();

    submit(); // the form was cleared, so this fails with "URL is required."

    expect(await screen.findByText(/url is required/i)).toBeInTheDocument();
    expect(successBanner()).not.toBeInTheDocument();
  });

  it('keeps the success banner while the user types the next URL', async () => {
    // The user may still want to copy the previous short URL.
    await createOne();

    await userEvent.type(screen.getByLabelText(/long url/i), 'https://next.example.com');

    expect(successBanner()).toBeInTheDocument();
  });
});
