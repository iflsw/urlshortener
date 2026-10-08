import { render, screen, fireEvent, within } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { UrlTable } from '../UrlTable';
import type { UrlListItem } from '../../types/api';

const mockUrls: UrlListItem[] = [
  { alias: 'abc', fullUrl: 'https://example.com', shortUrl: 'http://localhost:8080/abc' },
  { alias: 'def', fullUrl: 'https://another.com/path', shortUrl: 'http://localhost:8080/def' },
];

describe('UrlTable', () => {
  it('shows empty state when there are no URLs', () => {
    render(<UrlTable urls={[]} onDelete={vi.fn()} />);
    expect(screen.getByText(/no shortened urls yet/i)).toBeInTheDocument();
  });

  it('renders a row for each URL', () => {
    render(<UrlTable urls={mockUrls} onDelete={vi.fn()} />);
    expect(screen.getByText('http://localhost:8080/abc')).toBeInTheDocument();
    expect(screen.getByText('http://localhost:8080/def')).toBeInTheDocument();
  });

  it('calls onDelete with the alias when delete is clicked', () => {
    const onDelete = vi.fn();
    render(<UrlTable urls={mockUrls} onDelete={onDelete} />);
    fireEvent.click(screen.getAllByRole('button', { name: /delete/i })[0]);
    expect(onDelete).toHaveBeenCalledWith('abc');
  });

  it('renders a semantic table with column headers', () => {
    render(<UrlTable urls={mockUrls} onDelete={vi.fn()} />);
    const table = screen.getByRole('table');
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent)).toEqual([
      'Short URL',
      'Original URL',
      'Actions',
    ]);
    expect(within(table).getAllByRole('row')).toHaveLength(mockUrls.length + 1);
  });

  it('labels each delete button with its alias', () => {
    render(<UrlTable urls={mockUrls} onDelete={vi.fn()} />);
    expect(screen.getByRole('button', { name: 'Delete abc' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete def' })).toBeInTheDocument();
  });

  it('links short and full URLs in a new tab without leaking window.opener', () => {
    render(<UrlTable urls={mockUrls} onDelete={vi.fn()} />);
    const link = screen.getByRole('link', { name: 'https://example.com' });
    expect(link).toHaveAttribute('href', 'https://example.com');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(screen.getByRole('link', { name: 'http://localhost:8080/abc' })).toHaveAttribute(
      'rel',
      'noopener noreferrer',
    );
  });

  it('renders a non-http(s) URL as plain text, never as a link', () => {
    const unsafe: UrlListItem[] = [
      { alias: 'xss', fullUrl: 'javascript:alert(1)', shortUrl: 'http://localhost:8080/xss' },
    ];
    render(<UrlTable urls={unsafe} onDelete={vi.fn()} />);
    expect(screen.getByText('javascript:alert(1)').closest('a')).toBeNull();
  });

  it('disables delete buttons when disabled', () => {
    render(<UrlTable urls={mockUrls} onDelete={vi.fn()} disabled />);
    screen.getAllByRole('button', { name: /delete/i }).forEach((b) => expect(b).toBeDisabled());
  });
});
