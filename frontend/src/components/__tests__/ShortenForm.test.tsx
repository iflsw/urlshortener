import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { ShortenForm } from '../ShortenForm';

describe('ShortenForm', () => {
  it('renders the URL input and submit button', () => {
    render(<ShortenForm onSubmit={vi.fn()} />);
    expect(screen.getByLabelText(/long url/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /shorten url/i })).toBeInTheDocument();
  });

  it('shows a validation error when submitted with empty URL', async () => {
    render(<ShortenForm onSubmit={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: /shorten url/i }));
    expect(await screen.findByText(/url is required/i)).toBeInTheDocument();
  });

  it('shows a validation error for an invalid URL', async () => {
    render(<ShortenForm onSubmit={vi.fn()} />);
    await userEvent.type(screen.getByLabelText(/long url/i), 'not-a-url');
    fireEvent.click(screen.getByRole('button', { name: /shorten url/i }));
    expect(await screen.findByText(/valid url/i)).toBeInTheDocument();
  });

  it('shows a validation error for an invalid alias', async () => {
    render(<ShortenForm onSubmit={vi.fn()} />);
    await userEvent.type(screen.getByLabelText(/long url/i), 'https://example.com');
    await userEvent.type(screen.getByLabelText(/custom alias/i), 'bad alias');
    fireEvent.click(screen.getByRole('button', { name: /shorten url/i }));
    expect(await screen.findByText(/letters, numbers, and hyphens/i)).toBeInTheDocument();
  });

  it('calls onSubmit with fullUrl and no alias when alias is empty', async () => {
    const onSubmit = vi.fn().mockResolvedValue(true);
    render(<ShortenForm onSubmit={onSubmit} />);
    await userEvent.type(screen.getByLabelText(/long url/i), 'https://example.com');
    fireEvent.click(screen.getByRole('button', { name: /shorten url/i }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith('https://example.com', undefined));
  });

  it('calls onSubmit with customAlias when provided', async () => {
    const onSubmit = vi.fn().mockResolvedValue(true);
    render(<ShortenForm onSubmit={onSubmit} />);
    await userEvent.type(screen.getByLabelText(/long url/i), 'https://example.com');
    await userEvent.type(screen.getByLabelText(/custom alias/i), 'my-alias');
    fireEvent.click(screen.getByRole('button', { name: /shorten url/i }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith('https://example.com', 'my-alias'));
  });

  it('clears the form after successful submission', async () => {
    const onSubmit = vi.fn().mockResolvedValue(true);
    render(<ShortenForm onSubmit={onSubmit} />);
    const urlInput = screen.getByLabelText(/long url/i);
    await userEvent.type(urlInput, 'https://example.com');
    fireEvent.click(screen.getByRole('button', { name: /shorten url/i }));
    await waitFor(() => expect(urlInput).toHaveValue(''));
  });

  it('keeps what the user typed when the submission fails', async () => {
    // e.g. the API rejected the alias as taken: the user should be able to fix it, not retype it.
    const onSubmit = vi.fn().mockResolvedValue(false);
    render(<ShortenForm onSubmit={onSubmit} />);
    const urlInput = screen.getByLabelText(/long url/i);
    const aliasInput = screen.getByLabelText(/custom alias/i);
    await userEvent.type(urlInput, 'https://example.com');
    await userEvent.type(aliasInput, 'taken');
    fireEvent.click(screen.getByRole('button', { name: /shorten url/i }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByRole('button', { name: /shorten url/i })).toBeEnabled());
    expect(urlInput).toHaveValue('https://example.com');
    expect(aliasInput).toHaveValue('taken');
  });

  it('keeps what the user typed when onSubmit throws', async () => {
    const onSubmit = vi.fn().mockRejectedValue(new Error('network down'));
    render(<ShortenForm onSubmit={onSubmit} />);
    const urlInput = screen.getByLabelText(/long url/i);
    await userEvent.type(urlInput, 'https://example.com');
    fireEvent.click(screen.getByRole('button', { name: /shorten url/i }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByRole('button', { name: /shorten url/i })).toBeEnabled());
    expect(urlInput).toHaveValue('https://example.com');
  });

  it.each(['javascript:alert(1)', 'ftp://example.com/file', 'mailto:someone@example.com', 'data:text/html,hi'])(
    'rejects the non-http(s) URL %s without calling onSubmit',
    async (url) => {
      const onSubmit = vi.fn().mockResolvedValue(true);
      render(<ShortenForm onSubmit={onSubmit} />);
      await userEvent.type(screen.getByLabelText(/long url/i), url);
      fireEvent.click(screen.getByRole('button', { name: /shorten url/i }));

      expect(await screen.findByText(/valid url/i)).toBeInTheDocument();
      expect(onSubmit).not.toHaveBeenCalled();
    },
  );

  it.each(['http://example.com', 'https://example.com/path?q=1'])('accepts the http(s) URL %s', async (url) => {
    const onSubmit = vi.fn().mockResolvedValue(true);
    render(<ShortenForm onSubmit={onSubmit} />);
    await userEvent.type(screen.getByLabelText(/long url/i), url);
    fireEvent.click(screen.getByRole('button', { name: /shorten url/i }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(url, undefined));
  });

  it('calls onSubmitAttempt on every submit, before validation', async () => {
    // Lets the app drop the previous success banner even when validation then fails.
    const onSubmitAttempt = vi.fn();
    const onSubmit = vi.fn().mockResolvedValue(true);
    render(<ShortenForm onSubmit={onSubmit} onSubmitAttempt={onSubmitAttempt} />);

    fireEvent.click(screen.getByRole('button', { name: /shorten url/i })); // invalid: empty URL
    expect(await screen.findByText(/url is required/i)).toBeInTheDocument();
    expect(onSubmitAttempt).toHaveBeenCalledTimes(1);
    expect(onSubmit).not.toHaveBeenCalled();

    await userEvent.type(screen.getByLabelText(/long url/i), 'https://example.com');
    fireEvent.click(screen.getByRole('button', { name: /shorten url/i })); // valid
    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    expect(onSubmitAttempt).toHaveBeenCalledTimes(2);
  });
});
